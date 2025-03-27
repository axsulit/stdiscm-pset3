package com.consumer;

import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;
import java.io.File;
import java.util.Arrays;
import java.util.List;

@RestController
public class VideoUploadController {

    private static final String UPLOAD_DIR = "/uploads";

    @GetMapping("/videos")
    public List<String> listVideos() {
        File folder = new File(UPLOAD_DIR);
        return Arrays.stream(folder.listFiles())
                     .filter(File::isFile)
                     .map(File::getName)
                     .toList();
    }

    @GetMapping("/video/{name}")
    public FileSystemResource getVideo(@PathVariable String name) {
        return new FileSystemResource(UPLOAD_DIR + "/" + name);
    }
}
