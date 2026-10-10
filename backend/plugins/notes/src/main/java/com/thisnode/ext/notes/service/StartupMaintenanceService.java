package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.repository.NoteRepository;
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
        if (claimed + dropped > 0) {
            log.info("启动维护：认领 {} / 摘除 {}", claimed, dropped);
        }
    }
}
