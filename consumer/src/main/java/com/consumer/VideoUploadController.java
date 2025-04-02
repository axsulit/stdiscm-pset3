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

    private final Path uploadDir;
    private final Path tempDir;
    private final ConcurrentLinkedQueue<Path> uploadQueue;
    private final AtomicInteger currentQueueSize;
    private final int maxQueueLength;
    private final ExecutorService processingExecutor;

    public VideoUploadController() {
        // Use absolute paths
        this.uploadDir = Paths.get(System.getProperty("user.dir"), "uploads").toAbsolutePath();
        this.tempDir = Paths.get(System.getProperty("user.dir"), "temp").toAbsolutePath();
        
        // Create directories if they don't exist
        try {
            Files.createDirectories(uploadDir);
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            e.printStackTrace();
        }

        this.uploadQueue = new ConcurrentLinkedQueue<>();
        this.currentQueueSize = new AtomicInteger(0);
        this.maxQueueLength = ConfigLoader.getInt("queue.length", 5);
        this.processingExecutor = Executors.newSingleThreadExecutor();
        
        System.out.println("🚀 Consumer initialized with max queue length: " + maxQueueLength);
        System.out.println("📁 Upload directory: " + uploadDir);
        System.out.println("📁 Temp directory: " + tempDir);
        
        // Start the processing thread
        startProcessingThread();
    }

    private void startProcessingThread() {
        processingExecutor.submit(() -> {
            while (true) {
                try {
                    Path tempFilePath = uploadQueue.poll();
                    if (tempFilePath != null) {
                        processFile(tempFilePath);
                        currentQueueSize.decrementAndGet();
                        System.out.println("➖ Removed from queue: " + tempFilePath.getFileName());
                        System.out.println("📊 Queue size after processing: " + currentQueueSize.get() + "/" + maxQueueLength);
                    }
                    Thread.sleep(1000); // Process one file per second
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void processFile(Path tempFilePath) throws IOException {
        Path dest = uploadDir.resolve(tempFilePath.getFileName());
        Files.move(tempFilePath, dest);
        System.out.println("✅ Processed: " + dest);
    }

    @PostMapping("/upload")
    public ResponseEntity<String> handleUpload(@RequestParam("file") MultipartFile file) {
        System.out.println("\n📥 Received upload request for: " + file.getOriginalFilename());
        System.out.println("📊 Current queue size: " + currentQueueSize.get() + "/" + maxQueueLength);
        
        try {
            // Check for duplicate file in uploads directory
            Path existingFile = uploadDir.resolve(file.getOriginalFilename());
            if (Files.exists(existingFile)) {
                System.out.println("⚠️ Duplicate file detected: " + file.getOriginalFilename());
                return ResponseEntity.ok("Video dropped - Duplicate file");
            }

            // Check for duplicate in queue
            boolean isInQueue = uploadQueue.stream()
                .anyMatch(path -> path.getFileName().toString().equals(file.getOriginalFilename()));
            if (isInQueue) {
                System.out.println("⚠️ File already in queue: " + file.getOriginalFilename());
                return ResponseEntity.ok("Video dropped - Already in queue");
            }

            // Check if queue is full
            if (currentQueueSize.get() >= maxQueueLength) {
                System.out.println("⚠️ Queue full - Dropping video: " + file.getOriginalFilename());
                System.out.println("📊 Queue status: " + currentQueueSize.get() + "/" + maxQueueLength);
                return ResponseEntity.ok("Video dropped - Queue full");
            }

            // Save to temp directory first
            Path tempFile = tempDir.resolve(file.getOriginalFilename());
            file.transferTo(tempFile);
            
            // Add temp file path to queue
            uploadQueue.offer(tempFile);
            currentQueueSize.incrementAndGet();
            System.out.println("➕ Added to queue: " + file.getOriginalFilename());
            System.out.println("📊 Queue size after add: " + currentQueueSize.get() + "/" + maxQueueLength);

            return ResponseEntity.ok("Queued for processing: " + file.getOriginalFilename());
        } catch (Exception e) {
            System.out.println("❌ Failed to queue: " + file.getOriginalFilename());
            System.out.println("📊 Queue size after failure: " + currentQueueSize.get() + "/" + maxQueueLength);
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to queue: " + e.getMessage());
        }
    }

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
