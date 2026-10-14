package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.Card;
import com.thisnode.ext.notes.repository.CardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    private static final String IMAGE_FILE_NAME = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg|gif|webp|bmp|avif)";
    private static final Pattern IMAGE_FILE_NAME_PATTERN = Pattern.compile(IMAGE_FILE_NAME);
    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile("\\.\\./attachments/(" + IMAGE_FILE_NAME + ")");
    private static final Map<String, String> EXTENSIONS_BY_MEDIA_TYPE = Map.of("image/png",
                                                                               "png",
                                                                               "image/jpeg",
                                                                               "jpg",
                                                                               "image/gif",
                                                                               "gif",
                                                                               "image/webp",
                                                                               "webp",
                                                                               "image/bmp",
                                                                               "bmp",
                                                                               "image/x-ms-bmp",
                                                                               "bmp",
                                                                               "image/avif",
                                                                               "avif");
    private static final Map<String, String> MEDIA_TYPES_BY_EXTENSION = Map.of("png",
                                                                               "image/png",
                                                                               "jpg",
                                                                               "image/jpeg",
                                                                               "gif",
                                                                               "image/gif",
                                                                               "webp",
                                                                               "image/webp",
                                                                               "bmp",
                                                                               "image/bmp",
                                                                               "avif",
                                                                               "image/avif");

    private final NotesConfig notesConfig;
    private final CardRepository cardRepository;

    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("图片文件为空");

        String mediaType = file.getContentType();
        mediaType = mediaType == null ? "" : mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS_BY_MEDIA_TYPE.get(mediaType);
        if (extension == null) throw new IllegalArgumentException("不支持的图片格式");

        String fileName = UUID.randomUUID() + "." + extension;
        Path destination = imagePath(fileName);
        try {
            Files.createDirectories(destination.getParent());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, destination);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("保存图片附件失败", e);
        }
        log.info("保存图片附件 {}", fileName);
        return "../attachments/" + fileName;
    }

    public Path existingPath(String fileName) {
        Path path = imagePathOrNull(fileName);
        return path != null && Files.isRegularFile(path) ? path : null;
    }

    public MediaType mediaType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        return MediaType.parseMediaType(MEDIA_TYPES_BY_EXTENSION.get(extension));
    }

    public static Set<String> referencedFileNames(String markdown) {
        Set<String> fileNames = new HashSet<>();
        Matcher matcher = IMAGE_REFERENCE_PATTERN.matcher(markdown == null ? "" : markdown);
        while (matcher.find()) fileNames.add(matcher.group(1));
        return fileNames;
    }

    public void deleteIfUnreferenced(Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return;

        Set<String> stillReferenced = new HashSet<>();
        for (Card card : cardRepository.getAllIndexed()) {
            stillReferenced.addAll(referencedFileNames(card.getContent()));
        }

        for (String fileName : candidates) {
            if (stillReferenced.contains(fileName)) continue;
            Path path = imagePathOrNull(fileName);
            if (path == null) continue;
            try {
                Files.deleteIfExists(path);
                log.info("删除未引用的图片附件 {}", fileName);
            } catch (IOException e) {
                log.warn("无法删除未引用的图片附件 {}", fileName, e);
            }
        }
    }

    private Path imagePath(String fileName) {
        Path path = imagePathOrNull(fileName);
        if (path == null) throw new IllegalArgumentException("图片附件文件名无效");
        return path;
    }

    private Path imagePathOrNull(String fileName) {
        if (fileName == null || !IMAGE_FILE_NAME_PATTERN.matcher(fileName).matches()) return null;
        Path directory = notesConfig.getAttachmentsDir().toAbsolutePath().normalize();
        Path path = directory.resolve(fileName).normalize();
        return path.startsWith(directory) ? path : null;
    }
}
