package com.thisnote.ext.cardimporter.service;

import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.CardFutureEvent;
import com.thisnote.plugin.api.event.CardStageFutureEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

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

@Service
public class CardImporterService {

    private static final Logger log = LoggerFactory.getLogger(CardImporterService.class);
    private static final long SCAN_INTERVAL_MILLIS = 10_000;
    private static final long REFERENCE_QUERY_TIMEOUT_SECONDS = 30L;
    private static final String DEFAULT_BOOK_ID = "grp-default";

    private ApplicationEventPublisher mainEventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS)
    public synchronized void scan() {
        try {
            requestCardImports();
        } catch (Exception e) {
            log.error("扫描 notes/cards 提交导入申请失败", e);
        }
    }

    /**
     * 将没有卡片索引条目的 md（无主卡片）创建申请交给 Notes 处理。
     */
    private void requestCardImports() {
        CompletableFuture<List<CardDTO>> result = new CompletableFuture<>();
        mainEventPublisher.publishEvent(new CardFutureEvent(result));
        List<CardDTO> cards = result.join();
        if (cards.isEmpty()) return;
        log.info("卡片扫描发现无主卡片：count={}", cards.size());

        for (CardDTO card : cards) {
            String sourceFileName = card.sourceFileName();
            try {
                log.debug("提交无主卡片导入申请：sourceFileName={}", sourceFileName);
                mainEventPublisher.publishEvent(
                        CardChangedEvent.requestCardCreate(card, title(sourceFileName), DEFAULT_BOOK_ID));
            } catch (RuntimeException e) {
                log.error("提交无主卡片导入申请失败：sourceFileName={}", sourceFileName, e);
                throw e;
            }
        }
        log.info("已提交无主卡片导入申请：count={}", cards.size());
    }

    /**
     * 把用户输入文件夹中的 Markdown 复制到待导入卡片目录，解析交给定时任务。
     */
    public synchronized FolderImportResult importFolder(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            log.warn("拒绝卡片文件夹导入：路径为空");
            throw new IllegalArgumentException("文件夹路径不能为空");
        }
        Path input;
        try {
            input = Path.of(folderPath);
        } catch (RuntimeException e) {
            log.warn("拒绝卡片文件夹导入：path={}, reason=路径无效", folderPath, e);
            throw new IllegalArgumentException("文件夹路径无效：" + folderPath, e);
        }
        if (!input.isAbsolute()) {
            log.warn("拒绝卡片文件夹导入：path={}, reason=不是绝对路径", folderPath);
            throw new IllegalArgumentException("请输入绝对文件夹路径：" + folderPath);
        }
        Path folder = input.normalize();
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) {
            log.warn("拒绝卡片文件夹导入：folder={}, reason=文件夹不存在", folder);
            throw new IllegalArgumentException("文件夹不存在：" + folder);
        }

        log.info("开始卡片文件夹导入：folder={}", folder);
        List<Path> files;
        try (var paths = Files.walk(folder)) {
            files = paths
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(CardImporterService::isMarkdownCard)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            log.error("卡片文件夹读取失败：folder={}", folder, e);
            throw new IllegalStateException("无法读取文件夹：" + folder, e);
        }
        log.info("卡片文件夹筛选完成：folder={}, matched={}", folder, files.size());

        int imported = 0;
        int duplicates = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            String relativePath = folder.relativize(file).toString().replace('\\', '/');
            try {
                CardStageFutureEvent.CardStageResult staged = stageSelectedFile(file);
                if (staged.duplicated()) {
                    log.debug("跳过重复卡片文件名：file={}, fileName={}", file, staged.fileName());
                    duplicates++;
                } else if (!staged.fileName().isBlank()) {
                    log.debug("卡片文件已复制：source={}, stagedName={}", file, staged.fileName());
                    imported++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                errors.add(relativePath + "：" + e.getMessage());
                log.error("卡片文件夹导入失败：{}", file, e);
            }
        }
        log.info("卡片文件夹导入完成：folder={}, copied={}, duplicate={}, skipped={}, failed={}",
                folder, imported, duplicates, skipped, errors.size());
        return new FolderImportResult(imported, duplicates, skipped, errors);
    }

    /**
     * 接收浏览器上传的 Markdown 卡片，先写入临时目录，再复用 notes 的暂存事件。
     */
    public synchronized FolderImportResult importUpload(MultipartFile upload) {
        if (upload == null) {
            log.warn("拒绝卡片 HTTP 上传：reason=文件为空");
            return new FolderImportResult(0, 0, 1, List.of("未收到上传文件"));
        }
        String fileName = uploadedFileName(upload);
        String displayName = fileName.isBlank() ? "未命名文件" : fileName;
        if (upload.isEmpty()) {
            log.warn("拒绝卡片 HTTP 上传：fileName={}, reason=文件为空", displayName);
            return new FolderImportResult(0, 0, 1, List.of(displayName + "：文件为空"));
        }
        if (fileName.isBlank() || !isMarkdownCard(fileName)) {
            log.warn("拒绝卡片 HTTP 上传：fileName={}, reason=只支持 Markdown", displayName);
            return new FolderImportResult(0, 0, 1, List.of(displayName + "：只支持 .md 文件"));
        }

        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("thisnote-card-upload-");
            Path tempFile = tempDir.resolve(fileName);
            upload.transferTo(tempFile);
            log.info("卡片 HTTP 上传已暂存：fileName={}, bytes={}, tempFile={}",
                    fileName, upload.getSize(), tempFile);

            CardStageFutureEvent.CardStageResult staged = stageSelectedFile(tempFile);
            if (staged.duplicated()) {
                log.debug("跳过重复卡片文件名：fileName={}", staged.fileName());
                return new FolderImportResult(0, 1, 0, List.of());
            }
            if (!staged.fileName().isBlank()) {
                log.debug("卡片 HTTP 上传已复制：fileName={}, stagedName={}", fileName, staged.fileName());
                return new FolderImportResult(1, 0, 0, List.of());
            }
            log.warn("卡片 HTTP 上传未暂存：fileName={}", fileName);
            return new FolderImportResult(0, 0, 1, List.of());
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.error("卡片 HTTP 上传失败：fileName={}", fileName, e);
            return new FolderImportResult(0, 0, 0, List.of(displayName + "：" + reason));
        } finally {
            cleanup(tempDir);
        }
    }

    private CardStageFutureEvent.CardStageResult stageSelectedFile(Path source) {
        CardStageFutureEvent request = CardStageFutureEvent.request(
                source.toAbsolutePath().toString());
        mainEventPublisher.publishEvent(request);
        return request.result()
                .orTimeout(REFERENCE_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .join();
    }

    private String title(String fileName) {
        return fileName.substring(0, fileName.length() - ".md".length());
    }

    private static boolean isMarkdownCard(String fileName) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private static boolean isMarkdownCard(Path path) {
        return isMarkdownCard(path.getFileName().toString());
    }

    private static String uploadedFileName(MultipartFile upload) {
        String original = upload.getOriginalFilename();
        if (original == null) return "";
        String normalized = original.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (name.equals(".") || name.equals("..")) return "";
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private static void cleanup(Path tempDir) {
        if (tempDir == null) return;
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("卡片上传临时文件清理失败：path={}", path, e);
                }
            });
        } catch (IOException e) {
            log.warn("卡片上传临时目录清理失败：tempDir={}", tempDir, e);
        }
    }

    public record FolderImportResult(int imported,
                                     int duplicates,
                                     int skipped,
                                     List<String> errors) {
    }
}
