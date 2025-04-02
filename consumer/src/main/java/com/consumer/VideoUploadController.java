package com.consumer;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.shared.config.ConfigLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Set<String> fileHashes; // Store file hashes

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
        this.fileHashes = ConcurrentHashMap.newKeySet();
        
        System.out.println("🚀 Consumer initialized with max queue length: " + maxQueueLength);
        System.out.println("📁 Upload directory: " + uploadDir);
        System.out.println("📁 Temp directory: " + tempDir);
        
        // Initialize existing file hashes
        initializeExistingHashes();
        
        // Start the processing thread
        startProcessingThread();
    }

    private void initializeExistingHashes() {
        try {
            Files.list(uploadDir)
                .filter(path -> path.toString().endsWith(".mp4"))
                .forEach(path -> {
                    try {
                        String hash = calculateFileHash(path);
                        fileHashes.add(hash);
                        System.out.println("📝 Initialized hash for existing file: " + path.getFileName());
                    } catch (IOException e) {
                        System.out.println("❌ Failed to calculate hash for: " + path.getFileName());
                    }
                });
        } catch (IOException e) {
            System.out.println("❌ Failed to initialize existing hashes");
        }
    }

    private String calculateFileHash(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            try (InputStream is = Files.newInputStream(filePath)) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Failed to calculate hash", e);
        }
    }

    private String calculateFileHash(MultipartFile file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            try (InputStream is = file.getInputStream()) {
                while ((bytesRead = is.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Failed to calculate hash", e);
        }
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
            // Check if queue is full
            if (currentQueueSize.get() >= maxQueueLength) {
                System.out.println("⚠️ Queue full - Dropping video: " + file.getOriginalFilename());
                System.out.println("📊 Queue status: " + currentQueueSize.get() + "/" + maxQueueLength);
                return ResponseEntity.ok("Video dropped - Queue full");
            }

            // Calculate file hash
            String fileHash = calculateFileHash(file);
            System.out.println("🔍 File hash: " + fileHash);

            // Check for duplicates
            if (fileHashes.contains(fileHash)) {
                System.out.println("⚠️ Duplicate file detected - Skipping: " + file.getOriginalFilename());
                return ResponseEntity.ok("Video skipped - Duplicate detected");
            }

            // Save to temp directory first
            Path tempFile = tempDir.resolve(file.getOriginalFilename());
            file.transferTo(tempFile);
            
            // Add temp file path to queue and hash to set
            uploadQueue.offer(tempFile);
            currentQueueSize.incrementAndGet();
            fileHashes.add(fileHash);
            
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
