package com.thisnode.ext.attachmentimporter.service;

import com.thisnode.plugin.api.event.AttachmentAdoptEvent;
import com.thisnode.plugin.api.event.AttachmentQueryEvent;
import com.thisnode.plugin.api.event.CardChangedEvent;
import com.thisnode.plugin.api.notes.dto.AttachmentDTO;
import com.thisnode.plugin.api.notes.dto.CardDTO;
import com.thisnode.plugin.api.notes.dto.CardMetaDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 把「没有归属」的图片附件自动变成一篇笔记 + 一张卡片。
 *
 * <p>与 card-importer 同构：本插件只负责发现候选、提交创建申请；
 * 笔记与卡片由 notes 通过 {@link CardChangedEvent#requestCardCreate} 创建。
 *
 * <p>候选 = notes 通过 {@link AttachmentQueryEvent} 返回的「未被管理的附件」
 * （attachments 目录里、没有任何卡片引用的 png/jpg/webp）。
 * 不保存导入状态：是否已导入由「有没有卡片引用它」判断。
 *
 * <p>每个候选先经 {@link AttachmentAdoptEvent} 交给 notes：改名为 {@code <uuid>.<ext>}，
 * 原始文件名记入附件元数据；本插件再按新文件名建卡，标题取原文件名（去掉扩展名）。
 */
@Slf4j
@Service
public class AttachmentImporterService {

    private static final long SCAN_INTERVAL_MILLIS = 10_000L;
    private static final long REFERENCE_QUERY_TIMEOUT_SECONDS = 10L;
    private static final String DEFAULT_BOOK_ID = "grp-default";

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

    private final Path attachmentsDir;
    private ApplicationEventPublisher mainEventPublisher;

    public AttachmentImporterService(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        Path root = Path.of(dataRoot).toAbsolutePath().normalize();
        this.attachmentsDir = root.resolve("notes").resolve("attachments");
    }

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS, initialDelay = SCAN_INTERVAL_MILLIS)
    public void scan() {
        try {
            importNewAttachments();
        } catch (RuntimeException e) {
            log.warn("查询未被管理的附件失败", e);
        }
    }

    private void importNewAttachments() {
        CompletableFuture<List<AttachmentDTO>> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new AttachmentQueryEvent(result));
        List<AttachmentDTO> attachments = result.orTimeout(REFERENCE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();

        for (AttachmentDTO attachment : attachments) {
            String adopted = adopt(attachment.fileName());
            if (adopted != null) {
                importAttachment(attachment.fileName(), adopted);
            }
        }
    }

    /**
     * 交给 notes 把附件改名为 {@code <uuid>.<ext>}（原名记入元数据），返回新文件名。
     */
    private String adopt(String fileName) {
        CompletableFuture<AttachmentDTO> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new AttachmentAdoptEvent(result, fileName));
        try {
            AttachmentDTO adopted = result.orTimeout(REFERENCE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
            return adopted == null ? null : adopted.fileName();
        } catch (RuntimeException e) {
            log.warn("附件 {} 接管失败，下一轮重试", fileName, e);
            return null;
        }
    }

    private void importAttachment(String originalName,
                                  String fileName) {
        Path file = attachmentsDir.resolve(fileName);
        String title = titleOf(originalName);
        if (title.isBlank()) {
            log.warn("附件 {} 没有可用作标题的文件名，已跳过", originalName);
            return;
        }

        // 按图片原始像素尺寸给出初始卡片框体；读不到尺寸时用 notes 的默认宽高。
        CardMetaDTO meta = imageCardMeta(file);
        try {
            mainEventPublisher.publishEvent(CardChangedEvent.requestCardCreate(new CardDTO(meta, cardContent(title, fileName), null), title, DEFAULT_BOOK_ID));
        } catch (RuntimeException e) {
            log.warn("附件 {} 生成笔记/卡片失败，下一轮重试", originalName, e);
            return;
        }

        log.info("附件 {} 已接管为 {}，生成笔记「{}」", originalName, fileName, title);
    }

    private static String titleOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
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
        return new CardMetaDTO(null, null, null, 0d, 0d, cardWidth, cardHeight, 0, null, null, false, null);
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
}
