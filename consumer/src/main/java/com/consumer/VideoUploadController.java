package com.consumer;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.shared.config.ConfigLoader;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

/**
 * Controller class that handles video uploads and processing in a producer-consumer system.
 * This class manages a queue of video files, processes them using FFmpeg for compression,
 * and stores the results in an uploads directory.
 */
@RestController
public class VideoUploadController {
    // Constants for file handling and processing
    private static final String UPLOADS_DIR = "uploads";           // Directory for final processed videos
    private static final String TEMP_DIR = "temp";                 // Directory for temporary files
    private static final int QUEUE_POLL_DELAY_MS = 100;           // Delay between queue polling attempts
    private static final String FFMPEG_COMMAND = "ffmpeg";         // FFmpeg command for video processing
    private static final String[] FFMPEG_ARGS = {
        "-c:v", "libx264",        // H.264 video codec 
        "-crf", "32",             // Higher value = lower quality, smaller file size
        "-preset", "faster",      // Encoding speed preset
        "-r", "24",               // Output frame rate
        "-c:a", "aac",            // AAC audio codec 
        "-b:a", "64k",            // Audio bitrate
        "-vf", "scale=640:-2",    // Scale video width to 640px, maintain aspect ratio
        "-y"                      // Overwrite output file if it exists
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
        try {
            this.uploadDir = initializeDirectory(UPLOADS_DIR);
            this.tempDir = initializeDirectory(TEMP_DIR);
            
            this.uploadQueue = new ConcurrentLinkedQueue<>();
            this.currentQueueSize = new AtomicInteger(0);
            
            ConfigLoader.validateConsumerProperties();

            this.maxQueueLength = ConfigLoader.getInt("queue.length", 1);
            this.numConsumerThreads = ConfigLoader.getInt("consumer.threads", 1);
            
            this.processingExecutor = Executors.newFixedThreadPool(numConsumerThreads);
            
            logInitialization();
            startProcessingThreads();
        } catch (IllegalArgumentException e) {
            System.err.println("❌ Failed to start consumer: " + e.getMessage());
            throw e;
        }
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
     * Sanitizes a filename by replacing invalid characters with underscores.
     * @param filename The original filename
     * @return A sanitized filename safe for filesystem operations
     */
    private String sanitizeFilename(String filename) {
        // Replace problematic characters with underscores
        return filename.replaceAll("[\\\\/:*?\"<>|\\s]", "_");
    }

    /**
     * Processes a single video file, including compression and moving to final location.
     * @param tempFilePath Path to the temporary video file
     * @param threadId ID of the processing thread
     * @throws IOException if file operations fail
     */
    private void processVideoFile(Path tempFilePath, int threadId) throws IOException {
        String originalFilename = tempFilePath.getFileName().toString();
        String sanitizedFilename = sanitizeFilename(originalFilename);
        System.out.println("🎥 Thread " + threadId + " processing: " + originalFilename);
        
        // Use sanitized filename for all file operations
        Path compressedPath = tempDir.resolve(sanitizedFilename);
        Path finalPath = uploadDir.resolve(sanitizedFilename);

        try {
            // If the original file has special characters, rename it first
            if (!originalFilename.equals(sanitizedFilename)) {
                Path sanitizedTempPath = tempDir.resolve(sanitizedFilename);
                Files.move(tempFilePath, sanitizedTempPath, StandardCopyOption.REPLACE_EXISTING);
                tempFilePath = sanitizedTempPath;
            }

            boolean compressionSuccess = false;
            try {
                compressionSuccess = compressVideo(tempFilePath, compressedPath, sanitizedFilename);
            } catch (InterruptedException e) {
                System.out.println("⚠️ Video compression interrupted for: " + originalFilename);
                Thread.currentThread().interrupt();
            }

            if (compressionSuccess) {
                moveFile(compressedPath, finalPath, "compressed");
                // Only delete the temp file after successful move
                cleanupTempFiles(tempFilePath);
            } else {
                moveFile(tempFilePath, finalPath, "original");
            }
        } catch (Exception e) {
            // Clean up on error, but log the cleanup result
            System.out.println("❌ Error processing file: " + originalFilename);
            e.printStackTrace();
            try {
                cleanupTempFiles(tempFilePath, compressedPath);
            } catch (Exception cleanupError) {
                System.out.println("⚠️ Error during cleanup: " + cleanupError.getMessage());
            }
            throw e;
        }
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
        if (!Files.exists(inputPath)) {
            System.out.println("❌ Input file does not exist: " + inputPath);
            return false;
        }

        System.out.println("🎥 Compressing video: " + filename);
        System.out.println("📁 Input path: " + inputPath);

        // Create a temporary file path for the compressed video with timestamp to ensure uniqueness
        Path tempOutputPath = tempDir.resolve("temp_compressed_" + System.currentTimeMillis() + "_" + filename);
        System.out.println("📁 Temporary output path: " + tempOutputPath);

        // Build ffmpeg command
        List<String> command = new ArrayList<>();
        command.add(FFMPEG_COMMAND);
        command.add("-i");
        command.add(inputPath.toString());
        command.addAll(Arrays.asList(FFMPEG_ARGS));
        command.add(tempOutputPath.toString());

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        try {
            Process process = processBuilder.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[FFmpeg] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                // Ensure the destination doesn't exist before moving
                Files.deleteIfExists(outputPath);
                
                // Use atomic move operation
                try {
                    Files.move(tempOutputPath, outputPath, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // Fallback to regular move if atomic move is not supported
                    Files.move(tempOutputPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
                }
                
                System.out.println("✅ Compression successful for: " + filename);
                return true;
            } else {
                System.out.println("❌ FFmpeg exited with code " + exitCode + " for: " + filename);
                Files.deleteIfExists(tempOutputPath); // Clean up temp file if compression failed
                return false;
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("❌ Exception during compression of: " + filename);
            Files.deleteIfExists(tempOutputPath); // Clean up temp file on exception
            e.printStackTrace();
            throw e;
        }
    }

    /**
     * Moves a file from source to destination.
     * @param source Source path of the file
     * @param destination Destination path for the file
     * @param type Type of file being moved (for logging)
     * @throws IOException if the move operation fails
     */
    private void moveFile(Path source, Path destination, String type) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException("Source file does not exist: " + source);
        }
        
        // Ensure parent directory exists
        Files.createDirectories(destination.getParent());
        
        // If destination exists, generate a new unique name
        if (Files.exists(destination)) {
            String filename = destination.getFileName().toString();
            String baseName = filename;
            String extension = "";
            int dotIndex = filename.lastIndexOf('.');
            
            if (dotIndex > 0) {
                baseName = filename.substring(0, dotIndex);
                extension = filename.substring(dotIndex);
            }

            int copyNumber = 1;
            Path newDestination;
            do {
                newDestination = destination.getParent().resolve(baseName + "_" + copyNumber + extension);
                copyNumber++;
            } while (Files.exists(newDestination));
            
            destination = newDestination;
        }
        
        // Move the file
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
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
     * Generates a unique filename by appending a number if the file already exists.
     * @param originalFilename Original name of the file
     * @return A unique filename with _1, _2, etc. appended if necessary
     */
    private String generateUniqueFilename(String originalFilename) {
        String baseName = originalFilename;
        String extension = "";
        int dotIndex = originalFilename.lastIndexOf('.');
        
        if (dotIndex > 0) {
            baseName = originalFilename.substring(0, dotIndex);
            extension = originalFilename.substring(dotIndex);
        }

        String newFilename = originalFilename;
        int copyNumber = 0;
        
        while (Files.exists(uploadDir.resolve(sanitizeFilename(newFilename)))) {
            copyNumber++;
            newFilename = baseName + "_" + copyNumber + extension;
        }
        
        if (copyNumber > 0) {
            System.out.println("📝 Creating numbered copy: " + originalFilename + " → " + newFilename);
        }
        
        return newFilename;
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
     * Queues a file for processing.
     * @param file The uploaded file
     * @param uniqueFilename Unique name for the file
     * @return ResponseEntity with success message
     * @throws IOException if file operations fail
     */
    private ResponseEntity<String> queueFileForProcessing(MultipartFile file, String uniqueFilename) throws IOException {
        String sanitizedFilename = sanitizeFilename(uniqueFilename);
        Path tempFile = tempDir.resolve(sanitizedFilename);
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
}
