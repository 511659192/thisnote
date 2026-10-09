package com.thisnode.ext.notes.api;

import com.thisnode.ext.notes.service.NoteService;
import com.thisnode.ext.notes.domain.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Health 与维护端点。 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SystemController {

    private final NoteService noteService;
    private final NoteRepository noteRepository;

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
     * 重建派生数据：重算链接。搜索索引不用管——它就是内存里的笔记，
     * 内容一变它自动就是新的（数据库时代这里还要 OPTIMIZE TABLE 去救 InnoDB FULLTEXT）。
     */
    @PostMapping("/maintenance/reindex")
    public Map<String, Object> reindex() {
        long start = System.currentTimeMillis();
        int linkCount = noteService.reindexAllLinks();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("links", linkCount);
        result.put("search", "内存索引随内容即时更新，无需重建");
        result.put("mirrored", noteRepository.scanNotes().size());
        result.put("tookMs", System.currentTimeMillis() - start);
        noteService.log("system", null, "reindex", result);
        return result;
    }
}
