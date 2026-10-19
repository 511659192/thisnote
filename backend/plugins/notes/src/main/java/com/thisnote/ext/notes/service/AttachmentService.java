package com.thisnote.ext.notes.service;

import com.alibaba.fastjson2.TypeReference;
import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.model.CardPO;
import com.thisnote.ext.notes.repository.CardRepository;
import com.thisnote.plugin.api.notes.dto.AttachmentDTO;
import com.thisnote.plugin.api.util.FileHelper;
import com.thisnote.plugin.api.util.ImageSize;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AttachmentService {

    private static final String IMAGE_FILE_NAME = "[^/\n]+\\.(?:png|jpg|webp)";
    private static final Pattern IMAGE_FILE_NAME_PATTERN = Pattern.compile(IMAGE_FILE_NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile("\\.\\./attachments/(" + IMAGE_FILE_NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> EXTENSIONS_BY_MEDIA_TYPE = Map.of("image/png", "png", "image/jpeg", "jpg", "image/webp", "webp");
    private static final Map<String, String> MEDIA_TYPES_BY_EXTENSION = Map.of("png", "image/png", "jpg", "image/jpeg", "webp", "image/webp");

    private final NotesConfig notesConfig;
    private final CardRepository cardRepository;
    private final FileHelper fileHelper;
    private final Path attachmentMetadataFile;

    public AttachmentService(NotesConfig notesConfig,
                             CardRepository cardRepository,
                             FileHelper fileHelper) {
        this.notesConfig = notesConfig;
        this.cardRepository = cardRepository;
        this.fileHelper = fileHelper;
        this.attachmentMetadataFile = notesConfig.getRootDir().resolve("plugins").resolve("notes").resolve(".attachment-metadata.json");
    }

    public String save(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("图片文件为空");

        String mediaType = file.getContentType();
        mediaType = mediaType == null ? "" : mediaType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        String extension = EXTENSIONS_BY_MEDIA_TYPE.get(mediaType);
        if (extension == null) throw new IllegalArgumentException("不支持的图片格式");

        String attachmentId = UUID.randomUUID().toString();
        String fileName = attachmentId + "." + extension;
        Path destination = notesConfig.getRootDir().resolve("attachments").resolve(fileName);
        fileHelper.fileWritten(destination, file);
        putMetadata(fileName, buildMeta(attachmentId, fileName, null, destination, mediaType));
        return "../attachments/" + fileName;
    }

    /**
     * 接管一个未被管理的附件：改名为 {@code <uuid>.<ext>}，原始文件名记入元数据。
     *
     * @return 改名后的附件；文件不存在或名字非法时返回 null
     */
    public synchronized AttachmentDTO adopt(String fileName) {
        Path source = imagePathOrNull(fileName);
        if (source == null || !Files.isRegularFile(source)) return null;

        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String attachmentId = UUID.randomUUID().toString();
        String newFileName = attachmentId + "." + extension;
        Path target = notesConfig.getRootDir().resolve("attachments").resolve(newFileName);
        fileHelper.rename(source, target);

        CardPO.AttachmentMeta meta = buildMeta(attachmentId, newFileName, fileName, target, MEDIA_TYPES_BY_EXTENSION.get(extension));
        putMetadata(newFileName, meta);
        return new AttachmentDTO(newFileName, meta.size(), meta.w(), meta.h());
    }

    public Path existingPath(String fileName) {
        Path path = imagePathOrNull(fileName);
        return path != null && Files.isRegularFile(path) ? path : null;
    }

    public MediaType mediaType(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
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
     * <p>用于删除判断，以及计算「未被管理的附件」（{@link #unmanagedAttachments()}）。
     */
    public Set<String> allReferencedFileNames() {
        Set<String> fileNames = new HashSet<>();
        for (CardPO card : cardRepository.getAllManaged()) {
            fileNames.addAll(referencedFileNames(card.getContent()));
        }
        return fileNames;
    }

    /**
     * 未被管理的附件：attachments 目录里、没有任何卡片引用的 png/jpg/webp。
     */
    public List<AttachmentDTO> unmanagedAttachments() {
        Path dir = notesConfig.getRootDir().resolve("attachments");
        if (!Files.isDirectory(dir)) return List.of();

        // 先看目录里有没有候选图片：没有就不必付「全库卡片扫描」的代价（引用关系只存在于卡片正文中）。
        List<String> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (!fileName.startsWith(".") && isImageFile(fileName)) {
                    candidates.add(fileName);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取附件目录 " + dir, e);
        }
        if (candidates.isEmpty()) return List.of();

        Set<String> referenced = allReferencedFileNames();
        List<AttachmentDTO> attachments = new ArrayList<>();
        for (String fileName : candidates) {
            if (referenced.contains(fileName)) continue;
            Path path = dir.resolve(fileName);
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                continue;
            }
            if (attributes.isRegularFile() && attributes.size() > 0) {
                int[] pixels = ImageSize.of(path);
                attachments.add(new AttachmentDTO(
                        fileName,
                        attributes.size(),
                        pixels == null ? 0 : pixels[0],
                        pixels == null ? 0 : pixels[1]
                ));
            }
        }
        return attachments;
    }

    public void deleteIfUnreferenced(Set<String> candidates) {
        if (candidates == null || candidates.isEmpty()) return;

        Set<String> stillReferenced = allReferencedFileNames();
        for (String fileName : candidates) {
            if (stillReferenced.contains(fileName)) continue;
            Path path = imagePathOrNull(fileName);
            if (path == null) continue;
            if (fileHelper.fileDeleted(path)) {
                removeMetadata(fileName);
            }
        }
    }

    /**
     * 状态重置：把附件索引重写为空 map（附件文件保留）。
     */
    public synchronized void reset() {
        fileHelper.fileWritten(attachmentMetadataFile, new LinkedHashMap<>());
    }

    /**
     * 读取图片尺寸等元数据；读不出来时宽高记 0，不影响文件本身。
     */
    private CardPO.AttachmentMeta buildMeta(String attachmentId,
                                            String fileName,
                                            String originalName,
                                            Path file,
                                            String mediaType) {
        int width = 0;
        int height = 0;
        int[] pixels = ImageSize.of(file);
        if (pixels != null) {
            width = pixels[0];
            height = pixels[1];
        }
        return new CardPO.AttachmentMeta(attachmentId, fileName, originalName, null, null, width, height, file.toFile().length(), mediaType, System.currentTimeMillis());
    }

    private synchronized void putMetadata(String fileName,
                                          CardPO.AttachmentMeta meta) {
        Map<String, CardPO.AttachmentMeta> metas = readMetadata();
        metas.put(fileName, meta);
        fileHelper.fileWritten(attachmentMetadataFile, metas);
    }

    private synchronized void removeMetadata(String fileName) {
        Map<String, CardPO.AttachmentMeta> metas = readMetadata();
        if (metas.remove(fileName) != null) {
            fileHelper.fileWritten(attachmentMetadataFile, metas);
        }
    }

    private Map<String, CardPO.AttachmentMeta> readMetadata() {
        return FileHelper.readMapIndex(attachmentMetadataFile, new TypeReference<LinkedHashMap<String, CardPO.AttachmentMeta>>() { }, "附件");
    }

    /**
     * 文件名可以是任意名字，但必须是 png/jpg/webp 且不含路径分隔符。
     */
    private Path imagePathOrNull(String fileName) {
        if (fileName == null || fileName.indexOf('\\') >= 0) return null;
        if (!IMAGE_FILE_NAME_PATTERN.matcher(fileName).matches()) return null;
        return notesConfig.getRootDir().resolve("attachments").resolve(fileName);
    }

    private static boolean isImageFile(String fileName) {
        return IMAGE_FILE_NAME_PATTERN.matcher(fileName).matches();
    }
}
