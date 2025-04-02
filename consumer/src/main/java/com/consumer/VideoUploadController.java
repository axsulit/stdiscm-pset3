package com.consumer;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;

@RestController
public class VideoUploadController {

    private static final String UPLOAD_DIR = "./uploads"; 

    @PostMapping("/upload")
    public ResponseEntity<String> handleUpload(@RequestParam("file") MultipartFile file) {
        try {
            String uploadPath = new File(UPLOAD_DIR).getAbsolutePath();

            File uploadDir = new File(uploadPath);
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            File dest = new File(uploadDir, file.getOriginalFilename());
            file.transferTo(dest);

            System.out.println("✅ Uploaded: " + dest.getAbsolutePath());
            return ResponseEntity.ok("Uploaded: " + file.getOriginalFilename());
        } catch (IOException e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to upload: " + e.getMessage());
        }
    }

    @GetMapping("/list")
    @ResponseBody
    public List<String> listVideos() {
        File uploadDir = new File(UPLOAD_DIR);
        String[] files = uploadDir.list((dir, name) -> name.endsWith(".mp4"));
        return files != null ? Arrays.asList(files) : Collections.emptyList();
    }
}
