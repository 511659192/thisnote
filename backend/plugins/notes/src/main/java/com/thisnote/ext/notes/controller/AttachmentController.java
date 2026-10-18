package com.thisnote.ext.notes.controller;

import com.thisnote.ext.notes.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/notes/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public String upload(@RequestParam("file") MultipartFile file) {
        return attachmentService.save(file);
    }

    @PostMapping("/delete")
    public void deleteIfUnreferenced(@RequestParam String fileName) {
        attachmentService.deleteIfUnreferenced(java.util.Set.of(fileName));
    }

    @GetMapping("/get")
    public ResponseEntity<Resource> get(@RequestParam String fileName) {
        Path path = attachmentService.existingPath(fileName);
        if (path == null) return ResponseEntity.notFound().build();

        return ResponseEntity.ok()
                .contentType(attachmentService.mediaType(fileName))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(fileName).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noCache())
                .body(new FileSystemResource(path));
    }
}
