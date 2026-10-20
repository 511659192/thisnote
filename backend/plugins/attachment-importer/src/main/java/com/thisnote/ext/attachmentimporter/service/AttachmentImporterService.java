package com.thisnote.ext.attachmentimporter.service;

import com.thisnote.plugin.api.event.AttachmentAdoptFutureEvent;
import com.thisnote.plugin.api.event.AttachmentFutureEvent;
import com.thisnote.plugin.api.event.AttachmentStageFutureEvent;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 把「没有归属」的图片附件自动变成一篇笔记 + 一张卡片。
 *
 * <p>与 card-importer 同构：本插件只负责发现候选、提交创建申请；
 * 笔记与卡片由 notes 通过 {@link CardChangedEvent#requestCardCreate} 创建。
 *
 * <p>候选 = notes 通过 {@link AttachmentFutureEvent} 返回的「未被管理的附件」
 * （attachments 目录里、没有任何卡片引用的 png/jpg/webp）。
 * 自动扫描不保存导入状态：是否已导入由「有没有卡片引用它」判断。
 * 手动文件夹导入也不保存额外历史，由 notes 按待管理文件与附件原名判重。
 *
 * <p>每个候选先经 {@link AttachmentAdoptFutureEvent} 交给 notes：改名为 {@code <uuid>.<ext>}，
 * 原始文件名记入附件元数据；本插件再按新文件名建卡，标题取原文件名（去掉扩展名）。
 */
@Slf4j
@Service
public class AttachmentImporterService {

    private static final long SCAN_INTERVAL_MILLIS = 10_000L;
    private static final long REFERENCE_QUERY_TIMEOUT_SECONDS = 30L;
    private static final String DEFAULT_BOOK_ID = "grp-default";

    /**
     * 与界面侧卡片几何保持一致（frontend/plugins/notes/src/stores/notePage.ts 的
     * BLOCK_MIN_W / BLOCK_MIN_H）：宽度与高度只设下限，不封顶。
     */
    private static final double MIN_CARD_WIDTH = 240d;
    private static final double MIN_CARD_HEIGHT = 88d;
    /**
     * 卡片内容区外框：左右内边距 14×2 + 边框 1×2；上下内边距 30 + 12，再加边框 1×2。
     * 图片段落是唯一子节点，首尾 margin 为 0，所以卡片高 = 图片高 + 此值。
     */
    private static final double CARD_HORIZONTAL_CHROME = 30d;
    private static final double CARD_VERTICAL_CHROME = 44d;

    private ApplicationEventPublisher mainEventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS, initialDelay = SCAN_INTERVAL_MILLIS)
    public synchronized void scan() {
        try {
            importNewAttachments();
        } catch (Exception e) {
            log.error("查询未被管理的附件失败", e);
        }
    }

    private void importNewAttachments() {
        CompletableFuture<List<CardDTO.AttachmentDTO>> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new AttachmentFutureEvent(result));
        List<CardDTO.AttachmentDTO> attachments = result.orTimeout(REFERENCE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        if (!attachments.isEmpty()) {
            log.info("附件扫描发现候选：{}", attachments.size());
        }

        int imported = 0;
        int failed = 0;
        for (CardDTO.AttachmentDTO attachment : attachments) {
            CardDTO.AttachmentDTO adopted = adopt(attachment.fileName());
            if (adopted != null) {
                if (importAttachment(attachment.fileName(), adopted)) {
                    imported++;
                } else {
                    failed++;
                }
            } else {
                failed++;
            }
        }
        if (!attachments.isEmpty()) {
            log.info("附件扫描完成：候选={}, 已提交={}, 失败={}", attachments.size(), imported, failed);
        }
    }

    /**
     * 导入用户输入文件夹中的图片：只把文件复制到待管理附件目录，解析交给定时任务。
     */
    public synchronized FolderImportResult importFolder(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            log.warn("拒绝文件夹图片导入：路径为空");
            throw new IllegalArgumentException("文件夹路径不能为空");
        }
        Path input = Path.of(folderPath);
        if (!input.isAbsolute()) {
            log.warn("拒绝文件夹图片导入：path={}, reason=不是绝对路径", folderPath);
            throw new IllegalArgumentException("请输入绝对文件夹路径：" + folderPath);
        }
        Path folder = input.normalize();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("拒绝文件夹图片导入：folder={}, reason=文件夹不存在", folder);
            throw new IllegalArgumentException("文件夹不存在：" + folder);
        }
        log.info("开始文件夹图片导入：folder={}", folder);

        List<Path> files;
        try (var paths = Files.walk(folder)) {
            files = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(AttachmentImporterService::isSupportedImage)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            log.error("文件夹读取失败：folder={}", folder, e);
            throw new IllegalStateException("无法读取文件夹：" + folder, e);
        }
        log.info("文件夹图片筛选完成：folder={}, matched={}", folder, files.size());

        int imported = 0;
        int duplicates = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            String relativePath = folder.relativize(file).toString().replace('\\', '/');
            try {
                AttachmentStageFutureEvent.AttachmentStageResult staged = stageSelectedFile(file);
                if (staged.duplicated()) {
                    log.debug("跳过重复原文件名：file={}, fileName={}", file, staged.fileName());
                    duplicates++;
                } else if (!staged.fileName().isBlank()) {
                    log.debug("文件夹图片已复制：source={}, stagedName={}", file, staged.fileName());
                    imported++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                errors.add(relativePath + "：" + e.getMessage());
                log.error("文件夹图片导入失败：{}", file, e);
            }
        }
        log.info("文件夹图片导入完成：folder={}, copied={}, duplicate={}, skipped={}, failed={}",
                folder, imported, duplicates, skipped, errors.size());
        return new FolderImportResult(imported, duplicates, skipped, errors);
    }

    private AttachmentStageFutureEvent.AttachmentStageResult stageSelectedFile(Path source) {
        AttachmentStageFutureEvent request = AttachmentStageFutureEvent.request(
                source.toAbsolutePath().toString());
        mainEventPublisher.publishEvent(request);
        return request.result()
                .orTimeout(REFERENCE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
    }

    /**
     * 交给 notes 把附件改名为 {@code <uuid>.<ext>}（原名记入元数据），返回新文件名。
     */
    private CardDTO.AttachmentDTO adopt(String fileName) {
        CompletableFuture<CardDTO.AttachmentDTO> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new AttachmentAdoptFutureEvent(result, fileName));
        try {
            return result.orTimeout(REFERENCE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
        } catch (Exception e) {
            log.error("附件 {} 接管失败，下一轮重试", fileName, e);
            return null;
        }
    }

    private boolean importAttachment(String originalName,
                                     CardDTO.AttachmentDTO adopted) {
        String fileName = adopted.fileName();
        String title = titleOf(originalName);
        if (title.isBlank()) {
            log.warn("附件 {} 没有可用作标题的文件名，已跳过", originalName);
            return false;
        }

        // 按图片原始像素尺寸给出初始卡片框体；读不到尺寸时用 notes 的默认宽高。
        CardDTO.CardMetaDTO meta = imageCardMeta(adopted.width(), adopted.height());
        try {
            mainEventPublisher.publishEvent(CardChangedEvent.requestCardCreate(new CardDTO(meta, cardContent(title, fileName), null), title, DEFAULT_BOOK_ID));
        } catch (Exception e) {
            log.error("附件 {} 生成笔记/卡片失败，下一轮重试", originalName, e);
            return false;
        }

        log.info("附件 {} 已接管为 {}，生成笔记「{}」", originalName, fileName, title);
        return true;
    }

    private static String titleOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String title = dot > 0 ? fileName.substring(0, dot) : fileName;
        return title.replace('/', ' ').replace('\\', ' ').trim();
    }

    private static boolean isSupportedImage(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".webp");
    }

    private static String cardContent(String title,
                                      String fileName) {
        String alt = title.replace("[", "［").replace("]", "］").replace("\r", " ").replace("\n", " ");
        if (alt.length() > 80) {
            alt = alt.substring(0, 80);
        }
        return "![" + alt + "](<../attachments/" + fileName + ">)";
    }

    /**
     * 按图片原始像素尺寸给出卡片框体：内容区与图片 1:1，外框补上卡片内边距。
     *
     * <p>图片以 {@code max-width:100%}（不放大）渲染，卡片内容区宽 = 卡片宽 - 30，
     * 所以「宽 = 图片宽 + 30」时图片按原始尺寸显示；不设宽度上限，大图也不缩放。
     * 读不到尺寸（文件损坏）返回 {@code null}，由 notes 用默认 720×260。
     */
    private static CardDTO.CardMetaDTO imageCardMeta(int width,
                                                     int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        double cardWidth = Math.round(Math.max(MIN_CARD_WIDTH, width + CARD_HORIZONTAL_CHROME));
        double cardHeight = Math.round(Math.max(MIN_CARD_HEIGHT, height + CARD_VERTICAL_CHROME));
        return new CardDTO.CardMetaDTO(null, null, null, 0d, 0d, cardWidth, cardHeight, null, null, false, null);
    }

    public record FolderImportResult(int imported,
                                     int duplicates,
                                     int skipped,
                                     List<String> errors) {
    }
}
