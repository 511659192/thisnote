package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local image/file attachments (F-N-08, F-E-06, F-IO-05). */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    @PostMapping("/upload")
    public Dtos.AttachmentDto upload(@RequestParam("file") MultipartFile file) throws IOException {
        return attachmentService.store(file);
    }

    /**
     * Serve an attachment by its relative path, e.g. /api/attachments/2025/01/ab12-pic.png
     *
     * <p>路径通配在这里是**刻意保留的唯一例外**：附件 URL 以 {@code /api/attachments/<相对路径>}
     * 的形态**持久化在笔记正文里**（老内容全是这样），改成 ?path= 会让存量笔记的图片全部 404。
     * 新端点形态只此一处，不作为其它控制器的范式。
     */
    @GetMapping("/**")
    public ResponseEntity<Resource> serve(HttpServletRequest request) throws IOException {
        String full = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String rel = full == null ? "" : full.replaceFirst("^/api/attachments/?", "");
        rel = URLDecoder.decode(rel, StandardCharsets.UTF_8);
        Path file = attachmentService.resolve(rel);
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .contentType(attachmentService.mediaType(file))
                .body(new FileSystemResource(file));
    }

}
