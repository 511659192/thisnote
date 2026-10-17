package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.CardPO;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AttachmentService {

    private static final String IMAGE_FILE_NAME = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg)";
    private static final Pattern IMAGE_FILE_NAME_PATTERN = Pattern.compile(IMAGE_FILE_NAME);
    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile("\\.\\./attachments/(" + IMAGE_FILE_NAME + ")");
    private static final Map<String, String> EXTENSIONS_BY_MEDIA_TYPE = Map.of("image/png",
                                                                               "png",
                                                                               "image/jpeg",
                                                                               "jpg");
    private static final Map<String, String> MEDIA_TYPES_BY_EXTENSION = Map.of("png",
                                                                               "image/png",
                                                                               "jpg",
                                                                               "image/jpeg");

    private final NotesConfig notesConfig;
    private final CardRepository cardRepository;
    private final FileHelper fileHelper;

    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("图片文件为空");

        String mediaType = file.getContentType();
        mediaType = mediaType == null ? "" : mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS_BY_MEDIA_TYPE.get(mediaType);
        if (extension == null) throw new IllegalArgumentException("不支持的图片格式");

        String fileName = UUID.randomUUID() + "." + extension;
        Path destination = notesConfig.getRootDir().resolve("attachments").resolve(fileName);
        fileHelper.fileWritten(destination, file);
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

    /**
     * 当前被任意已索引卡片引用的附件文件名（只读快照）。
     *
     * <p>给「附件导入」这类插件查询用：它们不解析卡片内容，只按这份结果判断哪些附件还没有归属。
     */
    public Set<String> allReferencedFileNames() {
        Set<String> fileNames = new HashSet<>();
        for (CardPO card : cardRepository.getAllIndexed()) {
            fileNames.addAll(referencedFileNames(card.getContent()));
        }
        return fileNames;
    }

    public void deleteIfUnreferenced(Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return;

        Set<String> stillReferenced = allReferencedFileNames();
        for (String fileName : candidates) {
            if (stillReferenced.contains(fileName)) continue;
            Path path = imagePathOrNull(fileName);
            if (path == null) continue;
            fileHelper.fileDeleted(path);
        }
    }

    /**
     * 文件名必须严格匹配 UUID + 图片扩展名，因此不会再含路径分隔符或 `..`。
     */
    private Path imagePathOrNull(String fileName) {
        if (fileName == null || !IMAGE_FILE_NAME_PATTERN.matcher(fileName).matches()) return null;
        return notesConfig.getRootDir().resolve("attachments").resolve(fileName);
    }
}
