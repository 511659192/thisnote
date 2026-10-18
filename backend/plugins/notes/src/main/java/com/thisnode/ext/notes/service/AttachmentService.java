package com.thisnode.ext.notes.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.CardPO;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.plugin.api.notes.dto.AttachmentDTO;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AttachmentService {

    private static final String IMAGE_FILE_NAME = "[^/\n]+\\.(?:png|jpg)";
    private static final Pattern IMAGE_FILE_NAME_PATTERN = Pattern.compile(IMAGE_FILE_NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE_REFERENCE_PATTERN = Pattern.compile("\\.\\./attachments/(" + IMAGE_FILE_NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Map<String, String> EXTENSIONS_BY_MEDIA_TYPE = Map.of("image/png", "png", "image/jpeg", "jpg");
    private static final Map<String, String> MEDIA_TYPES_BY_EXTENSION = Map.of("png", "image/png", "jpg", "image/jpeg");

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
        return new AttachmentDTO(newFileName, meta.size());
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
     * 未被管理的附件：attachments 目录里、没有任何卡片引用的 png/jpg。
     */
    public List<AttachmentDTO> unmanagedAttachments() {
        Set<String> referenced = allReferencedFileNames();
        Path dir = notesConfig.getRootDir().resolve("attachments");
        List<AttachmentDTO> attachments = new ArrayList<>();
        if (!Files.isDirectory(dir)) return attachments;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (fileName.startsWith(".") || referenced.contains(fileName) || !isImageFile(fileName)) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                if (attributes.isRegularFile() && attributes.size() > 0) {
                    attachments.add(new AttachmentDTO(fileName, attributes.size()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取附件目录 " + dir, e);
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
     * 读取图片尺寸等元数据；ImageIO 读不出来时宽高记 0，不影响文件本身。
     */
    private CardPO.AttachmentMeta buildMeta(String attachmentId,
                                            String fileName,
                                            String originalName,
                                            Path file,
                                            String mediaType) {
        int width = 0;
        int height = 0;
        try {
            BufferedImage image = ImageIO.read(file.toFile());
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }
        } catch (IOException e) {
            log.warn("读取图片尺寸失败：{}", file, e);
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
        try {
            String json = Files.readString(attachmentMetadataFile, StandardCharsets.UTF_8);
            if (json.isBlank()) return new LinkedHashMap<>();
            Map<String, CardPO.AttachmentMeta> metas = JSON.parseObject(json, new TypeReference<LinkedHashMap<String, CardPO.AttachmentMeta>>() {
            });
            return metas == null ? new LinkedHashMap<>() : metas;
        } catch (Exception e) {
            log.error("附件索引无法解析：{}", attachmentMetadataFile, e);
            throw new IllegalStateException("附件索引无法解析 " + attachmentMetadataFile, e);
        }
    }

    /**
     * 文件名可以是任意名字，但必须是 png/jpg 且不含路径分隔符。
     */
    private Path imagePathOrNull(String fileName) {
        if (fileName == null || fileName.indexOf('\\') >= 0) return null;
        if (!IMAGE_FILE_NAME_PATTERN.matcher(fileName).matches()) return null;
        return notesConfig.getRootDir().resolve("attachments").resolve(fileName);
    }

    private static boolean isImageFile(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) return false;
        String extension = fileName.substring(dot + 1);
        return "png".equalsIgnoreCase(extension) || "jpg".equalsIgnoreCase(extension);
    }
}
