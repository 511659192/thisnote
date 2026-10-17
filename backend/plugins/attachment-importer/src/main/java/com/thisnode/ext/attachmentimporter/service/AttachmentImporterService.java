package com.thisnode.ext.attachmentimporter.service;

import com.thisnode.ext.attachmentimporter.model.ImportState;
import com.thisnode.ext.attachmentimporter.repository.ImportStateRepository;
import com.thisnode.plugin.api.event.AttachmentQueryEvent;
import com.thisnode.plugin.api.event.CardChangedEvent;
import com.thisnode.plugin.api.notes.dto.CardDTO;
import com.thisnode.plugin.api.notes.dto.CardMetaDTO;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 把「没有归属」的图片附件自动变成一篇笔记 + 一张卡片。
 *
 * <p>与 card-importer 同构：本插件只负责发现候选、规范化文件名、提交创建申请；
 * 笔记与卡片由 notes 通过 {@link CardChangedEvent#requestCardCreate} 创建。
 *
 * <p>候选 = 状态文件里没有 + 文件已存在至少 10 秒（避开「编辑器先传图、卡片稍后才保存」的窗口）
 * + 没有被任何卡片引用（向 notes 查询，见 {@link AttachmentQueryEvent}）。
 * 不建历史基线：状态文件缺失时（首次运行/被删除），当前目录里未被引用的图片都会被导入。
 *
 * <p>附件只有 {@code <uuid>.<png|jpg|gif|webp|bmp|avif>} 才能被界面渲染，所以非规范名会原地改名，
 * 原文件名保留为笔记标题；生成失败时把文件名回滚。
 */
@Slf4j
@Service
public class AttachmentImporterService {

    private static final long SCAN_INTERVAL_MILLIS = 10_000L;
    private static final long MIN_AGE_MILLIS = 10_000L;
    private static final long REFERENCE_QUERY_TIMEOUT_SECONDS = 10L;
    private static final String DEFAULT_BOOK_ID = "grp-default";

    private static final Set<String> IMAGE_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp", "avif");

    /**
     * 与界面侧卡片几何保持一致（frontend/plugins/notes/src/stores/notePage.ts 的
     * BLOCK_MIN_W / BLOCK_MIN_H / BLOCK_MAX_W）：宽度 240–680，高度只设下限。
     */
    private static final double MIN_CARD_WIDTH = 240d;
    private static final double MIN_CARD_HEIGHT = 88d;
    private static final double MAX_CARD_WIDTH = 680d;
    /**
     * 卡片内容区外框：左右内边距 14×2 + 边框 1×2；上下内边距 30 + 12，再加边框 1×2。
     * 图片段落是唯一子节点，首尾 margin 为 0，所以卡片高 = 图片高 + 此值。
     */
    private static final double CARD_HORIZONTAL_CHROME = 30d;
    private static final double CARD_VERTICAL_CHROME = 44d;
    /**
     * CardMetaDTO 的版本字段；建卡时只消费 w/h，这个值不参与。
     */
    private static final int CARD_META_SCHEMA = 2;

    /**
     * 与 {@code AttachmentService.IMAGE_FILE_NAME}、界面侧 Markdown 渲染识别的名字保持一致。
     */
    private static final Pattern CANONICAL_FILE_NAME = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(?:png|jpg|gif|webp|bmp|avif)");

    private final ImportStateRepository stateRepository;
    private final FileHelper fileHelper;
    private final Path attachmentsDir;
    private ApplicationEventPublisher mainEventPublisher;

    public AttachmentImporterService(ImportStateRepository stateRepository,
                                     FileHelper fileHelper,
                                     @Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        this.stateRepository = stateRepository;
        this.fileHelper = fileHelper;
        Path root = Path.of(dataRoot).toAbsolutePath().normalize();
        this.attachmentsDir = root.resolve("notes").resolve("attachments");
    }

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    /** 状态重置：清空导入记录，未引用附件会在下一轮扫描重新导入。 */
    public void clearState() {
        stateRepository.clear();
        log.info("附件导入状态已清空");
    }

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS, initialDelay = SCAN_INTERVAL_MILLIS)
    public void scan() {
        try {
            importNewAttachments();
        } catch (RuntimeException e) {
            log.warn("扫描附件目录失败", e);
        }
    }

    private void importNewAttachments() {
        if (!Files.isDirectory(attachmentsDir)) {
            return;
        }
        Map<String, Path> attachments = listAttachments();
        // 状态缺失（首次运行或被手动删除）从空状态开始：现有未引用附件会被导入。
        ImportState state = stateRepository.load();
        if (state == null) {
            state = new ImportState();
        }

        long now = System.currentTimeMillis();
        List<Map.Entry<String, Path>> candidates = new ArrayList<>();
        for (Map.Entry<String, Path> attachment : attachments.entrySet()) {
            if (state.getFiles().containsKey(attachment.getKey())) {
                continue;
            }
            if (!isOldEnough(attachment.getValue(), now)) {
                continue;
            }
            candidates.add(attachment);
        }
        if (candidates.isEmpty()) {
            return;
        }

        Set<String> referenced = referencedFileNames();
        for (Map.Entry<String, Path> candidate : candidates) {
            String fileName = candidate.getKey();
            if (referenced.contains(fileName)) {
                remember(state, fileName, snapshot(candidate.getValue()));
                log.debug("附件 {} 已被卡片引用，跳过导入", fileName);
                continue;
            }
            importAttachment(state, fileName, candidate.getValue());
        }
    }

    private void importAttachment(ImportState state,
                                  String fileName,
                                  Path file) {
        String title = titleOf(fileName);
        if (title.isBlank()) {
            remember(state, fileName, snapshot(file));
            log.warn("附件 {} 没有可用作标题的文件名，已跳过", fileName);
            return;
        }

        String canonicalName = canonicalName(fileName);
        boolean renamed = !canonicalName.equals(fileName);
        Path target = attachmentsDir.resolve(canonicalName);
        if (renamed) {
            try {
                fileHelper.rename(file, target);
            } catch (RuntimeException e) {
                log.warn("附件 {} 规范化文件名失败，下一轮重试", fileName, e);
                return;
            }
        }

        // 按图片原始像素尺寸给出初始卡片框体；读不到尺寸时用 notes 的默认宽高。
        CardMetaDTO meta = imageCardMeta(target);
        try {
            mainEventPublisher.publishEvent(CardChangedEvent.requestCardCreate(new CardDTO(meta, cardContent(title, canonicalName), null), title, DEFAULT_BOOK_ID));
        } catch (RuntimeException e) {
            if (renamed) {
                rollback(target, file);
            }
            log.warn("附件 {} 生成笔记/卡片失败，下一轮重试", fileName, e);
            return;
        }

        ImportState.ImportedFile imported = snapshot(target);
        imported.setNoteTitle(title);
        if (renamed) {
            imported.setSourceFileName(fileName);
        }
        remember(state, canonicalName, imported);
        if (renamed) {
            remember(state, fileName, imported);
        }
        log.info("附件 {} 已生成笔记「{}」（规范化名 {}）", fileName, title, canonicalName);
    }

    /**
     * 记录一条状态并立即落盘：导入过程中崩溃时，已处理的文件不会在下一轮被重复导入。
     */
    private void remember(ImportState state,
                          String fileName,
                          ImportState.ImportedFile entry) {
        state.getFiles().put(fileName, entry);
        stateRepository.save(state);
    }

    private void rollback(Path target,
                          Path original) {
        try {
            fileHelper.rename(target, original);
        } catch (RuntimeException e) {
            log.warn("附件文件名回滚失败：{} → {}", target, original, e);
        }
    }

    private Map<String, Path> listAttachments() {
        Map<String, Path> files = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(attachmentsDir)) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (!isImageFile(fileName)) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    continue;
                }
                if (attributes.isRegularFile() && attributes.size() > 0) {
                    files.put(fileName, path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法读取附件目录 " + attachmentsDir, e);
        }
        return files;
    }

    private Set<String> referencedFileNames() {
        CompletableFuture<Set<String>> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new AttachmentQueryEvent(result));
        return result.orTimeout(REFERENCE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private static boolean isImageFile(String fileName) {
        if (fileName.startsWith(".")) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && IMAGE_EXTENSIONS.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isOldEnough(Path file,
                                       long now) {
        try {
            return Files.getLastModifiedTime(file).toMillis() <= now - MIN_AGE_MILLIS;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 已是规范名就原样使用；否则生成 UUID 名（{@code jpeg} 归一成 {@code jpg}，与附件服务一致）。
     */
    private static String canonicalName(String fileName) {
        if (CANONICAL_FILE_NAME.matcher(fileName).matches()) {
            return fileName;
        }
        return UUID.randomUUID() + "." + extensionOf(fileName);
    }

    private static String extensionOf(String fileName) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return "jpeg".equals(extension) ? "jpg" : extension;
    }

    private static String titleOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String cardContent(String title,
                                      String canonicalName) {
        String alt = title.replace("[", "［").replace("]", "］").replace("\r", " ").replace("\n", " ");
        if (alt.length() > 80) {
            alt = alt.substring(0, 80);
        }
        return "![" + alt + "](../attachments/" + canonicalName + ")";
    }

    /**
     * 按图片原始像素尺寸折算卡片框体：宽度贴合图片（最多 680），高度随纵横比。
     *
     * <p>图片以 {@code max-width:100%}（不放大）渲染，卡片内容区宽 = 卡片宽 - 30，
     * 所以「宽 = 图片宽 + 30」时图片正好填满；超过 680 时图片按比例缩小。
     * 读不到尺寸（格式不被 ImageIO 支持、文件损坏）返回 {@code null}，由 notes 用默认 720×260。
     */
    private static CardMetaDTO imageCardMeta(Path file) {
        double[] pixels = imagePixelSize(file);
        if (pixels == null) {
            return null;
        }
        double naturalWidth = pixels[0];
        double naturalHeight = pixels[1];
        double renderedWidth = Math.min(naturalWidth, MAX_CARD_WIDTH - CARD_HORIZONTAL_CHROME);
        double renderedHeight = naturalHeight * (renderedWidth / naturalWidth);
        double cardWidth = Math.round(Math.max(MIN_CARD_WIDTH, Math.min(MAX_CARD_WIDTH, renderedWidth + CARD_HORIZONTAL_CHROME)));
        double cardHeight = Math.round(Math.max(MIN_CARD_HEIGHT, renderedHeight + CARD_VERTICAL_CHROME));
        return new CardMetaDTO(CARD_META_SCHEMA, null, null, null, 0d, 0d, cardWidth, cardHeight, 0, null, null, false, null);
    }

    private static double[] imagePixelSize(Path file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(file.toFile())) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                return width > 0 && height > 0 ? new double[]{width, height} : null;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            log.debug("读取图片尺寸失败：{}", file, e);
            return null;
        }
    }

    private static ImportState.ImportedFile snapshot(Path file) {
        ImportState.ImportedFile entry = new ImportState.ImportedFile();
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            entry.setSize(attributes.size());
            entry.setModifiedAt(attributes.lastModifiedTime().toMillis());
        } catch (IOException e) {
            log.debug("读取附件属性失败：{}", file, e);
        }
        return entry;
    }
}
