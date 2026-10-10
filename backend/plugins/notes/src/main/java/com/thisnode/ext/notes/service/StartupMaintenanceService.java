package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.repository.NoteRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * **启动维护**：认领游离的 md、把磁盘上已消失的卡片从清单里摘掉。
 *
 * <p>这是文件镜像的常驻对账，不是迁移——文件才是权威，外部改动（放进来的 md、删掉的文件）
 * 每次启动都对齐一次。无状态：每次都现扫目录与 meta。
 */
@Component
public class StartupMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(StartupMaintenanceService.class);

    private final NoteRepository noteRepository;

    public StartupMaintenanceService(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    @PostConstruct
    public void run() {
        noteRepository.ensureDirs();
        int claimed = noteRepository.claimUnmanaged();
        int dropped = noteRepository.dropMissingCards();
        int rebuilt = rebuildLinkCachesIfNeeded();
        if (claimed + dropped + rebuilt > 0) {
            log.info("启动维护：认领 {} / 摘除 {} / 补建链接缓存 {} 篇", claimed, dropped, rebuilt);
        }
    }

    /**
     * 链接缓存**全量重算一次**：出链按正文重算、入链按出链重建，然后落一个标记，之后随写入增量维护。
     *
     * <p>标记名带缓存的形状（{@code link-cache-from-to-raw}）：{@link com.thisnode.ext.notes.domain.Link}
     * 的字段改过名（{@code links}/{@code inbound} → {@code outLinks}/{@code inLinks}，
     * {@code id}/{@code target}/{@code context} → {@code from}/{@code to}/{@code raw}）—— 老 meta 里的旧字段
     * 会被当作未知字段忽略，不重算的话列表里的链接计数还在、详情面板却是空的。
     *
     * <p>**以后再改 {@code Link} 的字段，这里跟着改标记名**，否则老库不会重建。
     */
    private int rebuildLinkCachesIfNeeded() {
        Path marker = noteRepository.paths().noteMetaDir().resolve("link-cache-from-to-raw-counts");
        if (Files.exists(marker)) {
            return 0;
        }
        int refreshed = noteRepository.refreshAllCachedViews();
        int fixed = noteRepository.recalibrateInbound();
        try {
            Files.writeString(marker, String.valueOf(System.currentTimeMillis()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("无法写入 link-cache-v2 标记：{}", e.toString());
        }
        return refreshed + fixed;
    }
}
