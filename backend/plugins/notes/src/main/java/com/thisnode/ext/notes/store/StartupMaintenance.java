package com.thisnode.ext.notes.store;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * **启动维护**：认领游离的 md、把磁盘上已消失的卡片从清单里摘掉。
 *
 * <p>这是文件镜像的常驻对账，不是迁移——文件才是权威，外部改动（放进来的 md、删掉的文件）
 * 每次启动都对齐一次。无状态：每次都现扫目录与 meta。
 */
@Component
public class StartupMaintenance {

    private static final Logger log = LoggerFactory.getLogger(StartupMaintenance.class);

    private final NoteStore noteStore;
    private final NoteCompositionService composition;

    public StartupMaintenance(NoteStore noteStore, NoteCompositionService composition) {
        this.noteStore = noteStore;
        this.composition = composition;
    }

    @PostConstruct
    public void run() {
        noteStore.ensureDirs();
        int claimed = composition.claimUnmanaged();
        int dropped = composition.dropMissingCards();
        if (claimed + dropped > 0) {
            log.info("启动维护：认领 {} / 摘除 {}", claimed, dropped);
        }
    }
}
