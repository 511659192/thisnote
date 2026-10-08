package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.store.NoteMeta;
import com.thisnode.ext.notes.store.SnapshotLedger;
import com.thisnode.ext.notes.store.NoteStore;
import com.thisnode.ext.notes.util.Ids;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 本地快照（F-P-04：每天自动备份，保留最近 7 天）。
 *
 * <p>一个快照就是一个 zip：{@code notes/} 与 {@code attachments/}。文件成为权威之后，快照从
 * "数据库副本 + Markdown 导出"变成「**数据本身的一份拷贝**」—— 解压回去就是恢复，不再需要
 * 任何导入步骤，也不会出现"数据库和文件谁更新"的问题。
 *
 * <p>台账（有哪些快照、多大、覆盖多少篇笔记）记在 {@code .notes/snapshots.json}。
 */
@Service
@RequiredArgsConstructor
public class SnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final NotesConfig notesConfig;
    private final NoteRepository noteRepository;
    private final NoteStore noteStore;

    @Value("${thisnode.snapshot-auto:true}")
    private boolean autoEnabled;

    @Value("${thisnode.snapshot-retention-days:7}")
    private int retentionDays;

    public Path root() {
        return notesConfig.snapshotRoot();
    }

    /** Create a snapshot zip; returns its ledger entry. */
    public Dtos.SnapshotDto create(String kind) {
        String k = kind == null || kind.isBlank() ? "manual" : kind;
        String stamp = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + Instant.now().atZone(ZoneId.systemDefault()).format(STAMP);
        Path target = root().resolve(k + "-" + stamp + ".zip");
        try {
            Files.createDirectories(root());
            try (OutputStream raw = Files.newOutputStream(target);
                 ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(raw))) {
                zipTree(zip, notesConfig.markdownRoot(), "notes");
                zipTree(zip, notesConfig.attachmentRoot(), "attachments");
                zip.putNextEntry(new ZipEntry("README.txt"));
                zip.write(("thisnode 快照 kind=" + k + " created=" + Instant.now() + "\n"
                        + "notes/ 是权威数据本身（卡片 md + .notes/ 下的元数据），不是导出副本。\n"
                        + "恢复：关闭应用后，把 notes/ 解压覆盖回 " + notesConfig.markdownRoot() + " 即可。\n")
                        .getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            long size = Files.size(target);
            int nodeCount = (int) noteRepository.countActive();
            Dtos.SnapshotDto dto = new Dtos.SnapshotDto(Ids.newId(), k, target.toString(), size,
                    nodeCount, System.currentTimeMillis());
            saveLedger(ledger().with(toEntry(dto)));
            log.info("snapshot created: {} ({} bytes, {} notes)", target, size, nodeCount);
            return dto;
        } catch (Exception e) {
            log.error("snapshot failed", e);
            throw new IllegalStateException("快照失败：" + e.getMessage(), e);
        }
    }

    public List<Dtos.SnapshotDto> list() {
        List<Dtos.SnapshotDto> out = new ArrayList<>();
        for (SnapshotLedger.Entry entry : ledger().sorted()) {
            out.add(toDto(entry));
        }
        return out;
    }

    /** Delete the zip + its ledger entry. */
    public void delete(String id) {
        SnapshotLedger current = ledger();
        for (SnapshotLedger.Entry entry : current.entries()) {
            if (entry.id().equals(id)) {
                deleteQuietly(entry.path());
            }
        }
        saveLedger(current.without(id));
    }

    /** 保留最近 N 天：过期快照的文件与台账记录一起删。 */
    public int prune() {
        long cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600_000L;
        SnapshotLedger current = ledger();
        SnapshotLedger next = current;
        int pruned = 0;
        for (SnapshotLedger.Entry entry : current.entries()) {
            if (entry.createdAt() < cutoff) {
                deleteQuietly(entry.path());
                next = next.without(entry.id());
                pruned++;
            }
        }
        if (pruned > 0) {
            saveLedger(next);
        }
        return pruned;
    }

    /**
     * Runs shortly after startup and then hourly. The actual snapshot is taken at most
     * once per calendar day, and only when the note directory changed since the last one.
     */
    @Scheduled(initialDelay = 90_000, fixedDelay = 3600_000)
    public void autoSnapshot() {
        if (!autoEnabled) {
            return;
        }
        try {
            prune();
            long lastTs = 0;
            for (Dtos.SnapshotDto dto : list()) {
                if ("auto".equals(dto.kind())) {
                    lastTs = Math.max(lastTs, dto.createdAt());
                }
            }
            LocalDate today = LocalDate.now();
            if (lastTs > 0 && today.equals(Instant.ofEpochMilli(lastTs)
                    .atZone(ZoneId.systemDefault()).toLocalDate())) {
                return;
            }
            // "有没有变化"看内存索引里最新的 updatedAt：文件是权威，它比任何外部标记都准。
            long newest = 0;
            for (NoteMeta meta : noteStore.scanNotes()) {
                newest = Math.max(newest, meta.updatedAt());
            }
            if (newest <= lastTs) {
                return;
            }
            create("auto");
            prune();
        } catch (Exception e) {
            log.warn("auto snapshot skipped: {}", e.toString());
        }
    }

    public Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("root", root().toString());
        m.put("retentionDays", retentionDays);
        m.put("autoEnabled", autoEnabled);
        List<Dtos.SnapshotDto> all = list();
        m.put("count", all.size());
        m.put("latest", all.isEmpty() ? null : all.get(0));
        m.put("recent", new ArrayList<>(all.subList(0, Math.min(all.size(), 10))));
        return m;
    }

    // ------------------------------------------------------------------ helpers

    private static void zipTree(ZipOutputStream zip, Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                zip.putNextEntry(new ZipEntry(prefix + "/"
                        + root.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
    }

    private void deleteQuietly(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException e) {
            log.debug("could not delete snapshot file {}", path);
        }
    }

    private SnapshotLedger ledger() {
        SnapshotLedger loaded = noteStore.readMeta(noteStore.paths().snapshotsFile(), SnapshotLedger.class);
        return loaded == null ? SnapshotLedger.empty() : loaded;
    }

    private void saveLedger(SnapshotLedger next) {
        noteStore.writeJson(noteStore.paths().snapshotsFile(), next);
    }

    private static SnapshotLedger.Entry toEntry(Dtos.SnapshotDto dto) {
        return new SnapshotLedger.Entry(SnapshotLedger.SCHEMA, dto.id(), dto.kind(), dto.path(),
                dto.size(), dto.nodeCount(), dto.createdAt());
    }

    private static Dtos.SnapshotDto toDto(SnapshotLedger.Entry entry) {
        return new Dtos.SnapshotDto(entry.id(), entry.kind(), entry.path(), entry.size(),
                entry.nodeCount(), entry.createdAt());
    }
}
