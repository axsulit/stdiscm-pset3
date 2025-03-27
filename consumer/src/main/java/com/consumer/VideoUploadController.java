package com.consumer;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;

@RestController
public class VideoUploadController {

    private static final String UPLOAD_DIR = "./uploads"; 

    @PostMapping("/upload")
public ResponseEntity<String> handleUpload(@RequestParam("file") MultipartFile file) {
    try {
        // Get absolute path relative to application root
        //String uploadPath = new File("uploads").getAbsolutePath();
        String uploadPath = "/uploads"; 

        File uploadDir = new File(uploadPath);
        if (!uploadDir.exists()) {
            uploadDir.mkdirs();  // Create the directory if missing
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

}