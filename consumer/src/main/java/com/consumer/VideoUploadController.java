package com.consumer;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.shared.config.ConfigLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
public class VideoUploadController {
    // Constants for file handling and processing
    private static final String COMPRESSED_PREFIX = "compressed_";  // Prefix for compressed video files
    private static final String UPLOADS_DIR = "uploads";           // Directory for final processed videos
    private static final String TEMP_DIR = "temp";                 // Directory for temporary files
    private static final int QUEUE_POLL_DELAY_MS = 100;           // Delay between queue polling attempts
    private static final String FFMPEG_COMMAND = "ffmpeg";         // FFmpeg command for video processing
    private static final String[] FFMPEG_ARGS = {
        "-c:v", "libx264",    // Video codec
        "-crf", "28",         // Quality setting (18-28 is good, lower = better quality)
        "-preset", "slow",    // Encoding speed preset
        "-c:a", "aac",        // Audio codec
        "-b:a", "96k",        // Audio bitrate
        "-vf", "scale=1280:-2", // Scale video to 1280px width, keep aspect ratio
        "-y"                  // Overwrite output file if exists
    };

    // Instance variables for managing the video processing system
    private final Path uploadDir;                    // Directory for processed videos
    private final Path tempDir;                      // Directory for temporary files
    private final ConcurrentLinkedQueue<Path> uploadQueue;  // Queue for pending video processing
    private final AtomicInteger currentQueueSize;    // Current number of items in queue
    private final int maxQueueLength;                // Maximum allowed queue size
    private final ExecutorService processingExecutor; // Thread pool for processing videos
    private final int numConsumerThreads;            // Number of processing threads

    /**
     * Initializes the VideoUploadController with necessary directories and processing threads.
     * Sets up the queue system and starts the processing threads.
     */
    public VideoUploadController() {
        this.uploadDir = initializeDirectory(UPLOADS_DIR);
        this.tempDir = initializeDirectory(TEMP_DIR);
        
        this.uploadQueue = new ConcurrentLinkedQueue<>();
        this.currentQueueSize = new AtomicInteger(0);
        this.maxQueueLength = ConfigLoader.getInt("queue.length", 5);
        this.numConsumerThreads = ConfigLoader.getInt("consumer.threads", 1);
        
        this.processingExecutor = Executors.newFixedThreadPool(numConsumerThreads);
        
        logInitialization();
        startProcessingThreads();
    }

    /**
     * Creates and initializes a directory if it doesn't exist.
     * @param dirName The name of the directory to create
     * @return The Path object representing the created directory
     */
    private Path initializeDirectory(String dirName) {
        Path dir = Paths.get(System.getProperty("user.dir"), dirName).toAbsolutePath();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return dir;
    }

    /**
     * Logs the initialization parameters of the controller.
     */
    private void logInitialization() {
        System.out.println("🚀 Consumer initialized with:");
        System.out.println("   - Max queue length: " + maxQueueLength);
        System.out.println("   - Number of processing threads: " + numConsumerThreads);
        System.out.println("   - Upload directory: " + uploadDir);
        System.out.println("   - Temp directory: " + tempDir);
    }

    /**
     * Starts the processing threads that will handle video processing.
     */
    private void startProcessingThreads() {
        for (int i = 0; i < numConsumerThreads; i++) {
            final int threadId = i + 1;
            processingExecutor.submit(() -> processQueue(threadId));
        }
    }

    /**
     * Continuously processes videos from the queue.
     * @param threadId The ID of the processing thread
     */
    private void processQueue(int threadId) {
        System.out.println("🔄 Processing thread " + threadId + " started");
        while (true) {
            try {
                Path tempFilePath = uploadQueue.poll();
                if (tempFilePath != null) {
                    processVideoFile(tempFilePath, threadId);
                    currentQueueSize.decrementAndGet();
                    System.out.println("📊 Queue size after processing: " + currentQueueSize.get() + "/" + maxQueueLength);
                }
                Thread.sleep(QUEUE_POLL_DELAY_MS);
            } catch (Exception e) {
                logError("Error in processing thread " + threadId, e);
            }
        }
    }

    /**
     * Processes a single video file, including compression and moving to final location.
     * @param tempFilePath Path to the temporary video file
     * @param threadId ID of the processing thread
     * @throws IOException if file operations fail
     */
    private void processVideoFile(Path tempFilePath, int threadId) throws IOException {
        String originalFilename = tempFilePath.getFileName().toString();
        System.out.println("🎥 Thread " + threadId + " processing: " + originalFilename);
        
        String compressedFilename = COMPRESSED_PREFIX + originalFilename;
        Path compressedPath = tempDir.resolve(compressedFilename);
        Path finalPath = uploadDir.resolve(compressedFilename);

        try {
            if (handleExistingFile(finalPath, tempFilePath, originalFilename)) {
                return;
            }

            boolean compressionSuccess = false;
            try {
                compressionSuccess = compressVideo(tempFilePath, compressedPath, originalFilename);
            } catch (InterruptedException e) {
                System.out.println("⚠️ Video compression interrupted for: " + originalFilename);
                Thread.currentThread().interrupt();
            }

            if (compressionSuccess) {
                moveFile(compressedPath, finalPath, "compressed");
            } else {
                moveFile(tempFilePath, finalPath, "original");
            }
        } finally {
            cleanupTempFiles(tempFilePath, compressedPath);
        }
    }

    /**
     * Handles the case where a file already exists in the uploads directory.
     * @param finalPath Path where the file would be stored
     * @param tempFilePath Path to the temporary file
     * @param originalFilename Original name of the file
     * @return true if file exists and was handled, false otherwise
     * @throws IOException if file operations fail
     */
    private boolean handleExistingFile(Path finalPath, Path tempFilePath, String originalFilename) throws IOException {
        if (Files.exists(finalPath)) {
            System.out.println("⚠️ File already exists in uploads: " + originalFilename);
            Files.deleteIfExists(tempFilePath);
            return true;
        }
        return false;
    }

    /**
     * Compresses a video file using FFmpeg.
     * @param inputPath Path to the input video file
     * @param outputPath Path where the compressed video will be saved
     * @param filename Name of the video file
     * @return true if compression was successful, false otherwise
     * @throws IOException if file operations fail
     * @throws InterruptedException if the compression process is interrupted
     */
    private boolean compressVideo(Path inputPath, Path outputPath, String filename) throws IOException, InterruptedException {
        System.out.println("🎥 Compressing video: " + filename);
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(FFMPEG_COMMAND, "-i", inputPath.toString());
        processBuilder.command().addAll(Arrays.asList(FFMPEG_ARGS));
        processBuilder.command().add(outputPath.toString());

        Process process = processBuilder.start();
        int exitCode = process.waitFor();
        
        return exitCode == 0;
    }

    /**
     * Moves a file from source to destination.
     * @param source Source path of the file
     * @param destination Destination path for the file
     * @param type Type of file being moved (for logging)
     * @throws IOException if the move operation fails
     */
    private void moveFile(Path source, Path destination, String type) throws IOException {
        Files.move(source, destination);
        System.out.println("✅ Moved " + type + " video to: " + destination);
    }

    /**
     * Cleans up temporary files after processing.
     * @param files Array of file paths to clean up
     */
    private void cleanupTempFiles(Path... files) {
        for (Path file : files) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                System.out.println("⚠️ Failed to clean up temp file: " + file + " - " + e.getMessage());
            }
        }
    }

    /**
     * Generates a unique filename to avoid conflicts.
     * @param originalFilename Original name of the file
     * @return A unique filename
     */
    private String generateUniqueFilename(String originalFilename) {
        String baseName = originalFilename;
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        
        if (dotIndex > 0) {
            baseName = originalFilename.substring(0, dotIndex);
            extension = originalFilename.substring(dotIndex);
        }

        int copyNumber = 1;
        String newFilename = originalFilename;
        
        while (isFilenameTaken(newFilename)) {
            copyNumber++;
            newFilename = baseName + "_" + copyNumber + extension;
        }

        if (copyNumber > 1) {
            System.out.println("📝 Renaming duplicate file: " + originalFilename + " → " + newFilename);
        }
        
        return newFilename;
    }

    /**
     * Checks if a filename is already in use in the uploads directory or queue.
     * @param filename The filename to check
     * @return true if the filename is taken, false otherwise
     */
    private boolean isFilenameTaken(String filename) {
        // Check uploads directory
        if (Files.exists(uploadDir.resolve(COMPRESSED_PREFIX + filename))) {
            return true;
        }

        // Check queue
        final String currentFilename = filename;
        return uploadQueue.stream()
            .anyMatch(path -> path.getFileName().toString().equals(currentFilename));
    }

    /**
     * Handles incoming video upload requests.
     * @param file The uploaded video file
     * @return ResponseEntity containing the status of the upload
     */
    @PostMapping("/upload")
    public ResponseEntity<String> handleUpload(@RequestParam("file") MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        System.out.println("\n📥 Received upload request for: " + originalFilename);
        System.out.println("📊 Current queue size: " + currentQueueSize.get() + "/" + maxQueueLength);
        
        try {
            String uniqueFilename = generateUniqueFilename(originalFilename);
            
            if (isQueueFull()) {
                return handleQueueFull(uniqueFilename);
            }

            if (isFileAlreadyExists(uniqueFilename)) {
                return handleExistingFile(uniqueFilename);
            }

            return queueFileForProcessing(file, uniqueFilename);
        } catch (Exception e) {
            return handleUploadError(originalFilename, e);
        }
    }

    /**
     * Checks if the processing queue is full.
     * @return true if the queue is full, false otherwise
     */
    private boolean isQueueFull() {
        return currentQueueSize.get() >= maxQueueLength;
    }

    /**
     * Handles the case where the queue is full.
     * @param filename Name of the file that couldn't be queued
     * @return ResponseEntity with appropriate error message
     */
    private ResponseEntity<String> handleQueueFull(String filename) {
        System.out.println("⚠️ Queue full - Rejecting upload: " + filename);
        System.out.println("📊 Queue status: " + currentQueueSize.get() + "/" + maxQueueLength);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body("Queue full - Please wait before uploading more videos");
    }

    /**
     * Checks if a file already exists in the uploads directory.
     * @param filename Name of the file to check
     * @return true if the file exists, false otherwise
     */
    private boolean isFileAlreadyExists(String filename) {
        return Files.exists(uploadDir.resolve(COMPRESSED_PREFIX + filename));
    }

    /**
     * Handles the case where a file already exists in uploads.
     * @param filename Name of the existing file
     * @return ResponseEntity with appropriate message
     */
    private ResponseEntity<String> handleExistingFile(String filename) {
        System.out.println("⚠️ File already exists in uploads: " + filename);
        return ResponseEntity.ok("File already exists in uploads: " + filename);
    }

    /**
     * Queues a file for processing.
     * @param file The uploaded file
     * @param uniqueFilename Unique name for the file
     * @return ResponseEntity with success message
     * @throws IOException if file operations fail
     */
    private ResponseEntity<String> queueFileForProcessing(MultipartFile file, String uniqueFilename) throws IOException {
        Path tempFile = tempDir.resolve(uniqueFilename);
        file.transferTo(tempFile);
        
        uploadQueue.offer(tempFile);
        currentQueueSize.incrementAndGet();
        
        System.out.println("➕ Added to queue: " + uniqueFilename);
        System.out.println("📊 Queue size after add: " + currentQueueSize.get() + "/" + maxQueueLength);

        return ResponseEntity.ok("Queued for processing: " + uniqueFilename);
    }

    /**
     * Handles errors during file upload.
     * @param filename Name of the file that failed to upload
     * @param e The exception that occurred
     * @return ResponseEntity with error message
     */
    private ResponseEntity<String> handleUploadError(String filename, Exception e) {
        System.out.println("❌ Failed to queue: " + filename);
        System.out.println("📊 Queue size after failure: " + currentQueueSize.get() + "/" + maxQueueLength);
        e.printStackTrace();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Failed to queue: " + e.getMessage());
    }

    /**
     * Logs an error message and its associated exception.
     * @param message The error message
     * @param e The exception that occurred
     */
    private void logError(String message, Exception e) {
        System.out.println("❌ " + message + ": " + e.getMessage());
        e.printStackTrace();
    }

    /**
     * Lists all processed videos in the uploads directory.
     * @return List of video filenames
     */
    @GetMapping("/list")
    @ResponseBody
    public List<String> listVideos() {
        try {
            return Files.list(uploadDir)
                    .filter(path -> path.toString().endsWith(".mp4"))
                    .map(path -> path.getFileName().toString())
                    .toList();
        } catch (IOException e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    @GetMapping("/queue-status")
    @ResponseBody
    public ResponseEntity<String> getQueueStatus() {
        String status = String.format("Queue size: %d/%d", currentQueueSize.get(), maxQueueLength);
        System.out.println("📊 Queue status check: " + status);
        return ResponseEntity.ok(status);
    }
}
