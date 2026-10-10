package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.config.NotesConfig;
import cn.hutool.core.util.IdUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;

/** Local attachment store (F-N-08, F-E-06, F-IO-05). */
@Service
public class AttachmentService {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);

    private final NotesConfig config;

    public AttachmentService(NotesConfig config) {
        this.config = config;
    }

    public Path root() {
        return config.attachmentRoot();
    }

    /** Store an uploaded file under attachments/YYYY/MM/ and return its reference. */
    public Dtos.AttachmentDto store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("空文件");
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String safe = sanitize(original);
        LocalDate today = LocalDate.now();
        String folder = String.format("%04d/%02d", today.getYear(), today.getMonthValue());
        Path dir = root().resolve(folder);
        Files.createDirectories(dir);

        String unique = IdUtil.fastSimpleUUID().substring(0, 8) + "-" + safe;
        Path target = dir.resolve(unique);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        String rel = folder + "/" + unique;
        return new Dtos.AttachmentDto(original, "/api/attachments/" + rel, Files.size(target));
    }


    /** Resolve a relative attachment path, refusing traversal outside the store. */
    public Path resolve(String relative) {
        String rel = relative == null ? "" : relative.replace('\\', '/');
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path resolved = root().resolve(rel).normalize();
        if (!resolved.startsWith(root())) {
            throw new SecurityException("非法附件路径");
        }
        return resolved;
    }

    public MediaType mediaType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "svg" -> MediaType.parseMediaType("image/svg+xml");
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "json" -> MediaType.APPLICATION_JSON;
            case "txt", "md" -> MediaType.TEXT_PLAIN;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private static String sanitize(String name) {
        String base = name.replaceAll("[\\\\/]", "-").replaceAll("[\\p{Cntrl}]", "");
        base = base.replaceAll("[\\s]+", "_");
        if (base.isBlank()) {
            base = "file";
        }
        return base.length() > 80 ? base.substring(base.length() - 80) : base;
    }

}
