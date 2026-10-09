package com.thisnode.ext.notes.store;

import com.thisnode.ext.notes.domain.NoteRepository;
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
public class StartupMaintenance {

    private static final Logger log = LoggerFactory.getLogger(StartupMaintenance.class);

    private final NoteRepository noteRepository;

    public StartupMaintenance(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    @PostConstruct
    public void run() {
        noteRepository.ensureDirs();
        int claimed = noteRepository.claimUnmanaged();
        int dropped = noteRepository.dropMissingCards();
        int rebuilt = rebuildInboundIfNeeded();
        if (claimed + dropped + rebuilt > 0) {
            log.info("启动维护：认领 {} / 摘除 {} / 补建入链缓存 {} 篇", claimed, dropped, rebuilt);
        }
    }

    /** 老 meta 没有入链清单（标记 {@code inbound-v1} 之前的数据）：补建一次，之后随写入增量维护。 */
    private int rebuildInboundIfNeeded() {
        Path marker = noteRepository.paths().noteMetaDir().resolve("inbound-v1");
        if (Files.exists(marker)) {
            return 0;
        }
        int fixed = noteRepository.recalibrateInbound();
        try {
            Files.writeString(marker, String.valueOf(System.currentTimeMillis()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("无法写入 inbound-v1 标记：{}", e.toString());
        }
        return fixed;
    }
}
