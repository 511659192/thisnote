package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.LinkRepository;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.domain.OpsRepository;
import com.thisnode.ext.notes.domain.TagRepository;
import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.service.NoteSyncService;
import com.thisnode.ext.notes.service.SnapshotService;
import com.thisnode.ext.notes.store.NoteStore;
import com.thisnode.ext.notes.store.NoteCompositionService;
import com.thisnode.ext.notes.store.NoteMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Health, settings, snapshots, note-directory status and repair endpoints. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SystemController {

    private final NoteRepository noteRepository;
    private final OpsRepository opsRepository;
    private final SnapshotService snapshotService;
    private final NoteSyncService noteSync;
    private final NoteService noteService;
    private final NotesConfig notesConfig;
    private final NoteStore noteStore;
    private final NoteCompositionService composition;
    private final LinkRepository linkRepository;
    private final TagRepository tagRepository;

    @Value("${server.port:45917}")
    private int port;

    private final long startedAt = System.currentTimeMillis();

    /** Liveness probe（探活）：脚本、容器编排与健康检查都靠它确认后端已经就绪。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "ok",
                "app", "thisnode-backend",
                "version", "0.1.0",
                "port", port,
                "pid", ProcessHandle.current().pid(),
                "uptimeMs", System.currentTimeMillis() - startedAt,
                "jvm", System.getProperty("java.version"));
    }

    /**
     * {@code dbPath} 字段的语义随存储模型一起变了：以前是数据库位置，现在是**数据根目录**。
     * 字段名保持不变，因为界面与脚本仍在读它。
     */
    @GetMapping("/info")
    public Dtos.SystemInfo info() {
        return new Dtos.SystemInfo("0.1.0",
                notesConfig.dataRoot().toString(),
                notesConfig.markdownRoot().toString(),
                notesConfig.attachmentRoot().toString(),
                notesConfig.snapshotRoot().toString(),
                noteRepository.countActive(),
                composition.allBlocks().size(),
                ManagementFactory.getRuntimeMXBean().getUptime());
    }

    // ---------------- snapshots ----------------

    @GetMapping("/snapshots")
    public Map<String, Object> snapshots() {
        return snapshotService.info();
    }

    @PostMapping("/snapshots")
    public Dtos.SnapshotDto createSnapshot(@RequestParam(defaultValue = "manual") String kind) {
        Dtos.SnapshotDto dto = snapshotService.create(kind);
        noteService.log("system", dto.id(), "snapshot", Map.of("kind", kind));
        return dto;
    }

    @DeleteMapping("/snapshots/{id}")
    public Map<String, Object> deleteSnapshot(@PathVariable String id) {
        snapshotService.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/snapshots/prune")
    public Map<String, Object> pruneSnapshots() {
        return Map.of("pruned", snapshotService.prune());
    }

    // ---------------- notes sync (the Markdown directory is the data) ----------------

    @GetMapping("/notes/sync/status")
    public Map<String, Object> notesSyncStatus() {
        Map<String, Object> status = noteSync.status();
        // 未纳管的文件会被自动认领，所以"孤儿"这个状态不存在了；字段留着为了不打断界面。
        status.put("orphans", List.of());
        return status;
    }

    @PostMapping("/notes/sync/enabled")
    public Map<String, Object> setNotesSyncEnabled(@RequestParam boolean value) {
        noteSync.setEnabled(value);
        opsRepository.putSetting("note-watch", value ? "true" : "false");
        return Map.of("enabled", noteSync.enabled());
    }

    @PostMapping("/notes/sync/reconcile")
    public Map<String, Object> reconcile() {
        int applied = noteSync.reconcile();
        return Map.of("applied", applied, "events", noteSync.recentEvents());
    }

    // ---------------- maintenance ----------------

    /**
     * 重建派生数据：重扫笔记目录并重算链接。搜索索引不用管——它就是内存里的笔记，
     * 内容一变它自动就是新的（数据库时代这里还要 OPTIMIZE TABLE 去救 InnoDB FULLTEXT）。
     */
    @PostMapping("/maintenance/reindex")
    public Map<String, Object> reindex() {
        long start = System.currentTimeMillis();
        int linkCount = noteService.reindexAllLinks();
        noteSync.rescanNow();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("links", linkCount);
        result.put("search", "内存索引随内容即时更新，无需重建");
        result.put("mirrored", noteStore.scanNotes().size());
        result.put("tookMs", System.currentTimeMillis() - start);
        noteService.log("system", null, "reindex", result);
        return result;
    }

    @GetMapping("/maintenance/stats")
    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<NoteMeta> all = noteStore.scanNotes();
        int trash = 0;
        for (NoteMeta meta : all) {
            if (meta.deleted()) {
                trash++;
            }
        }
        m.put("nodes", all.size());
        m.put("trash", trash);
        m.put("links", linkRepository.total());
        m.put("tags", tagRepository.listWithCounts().size());
        m.put("noteBlocks", composition.allBlocks().size());
        m.put("ops", opsRepository.count());
        m.put("snapshots", snapshotService.list().size());
        m.put("startedAt", Instant.ofEpochMilli(startedAt).toString());
        m.put("markdownRoot", notesConfig.markdownRoot().toString());
        m.put("dbVendor", "files");
        return m;
    }
}
