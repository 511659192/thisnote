package com.thisnote.ext.tags.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Refreshes affected notes off the Notes event-publishing thread.
 */
@Slf4j
@Service
public class TagUsageRefreshService {

    private static final long DEBOUNCE_MILLIS = 400L;

    private final TagsService tagsService;
    private final Set<String> pending = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "thisnote-tags-usage-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public TagUsageRefreshService(TagsService tagsService) {
        this.tagsService = tagsService;
    }

    public void request(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            log.debug("忽略空笔记 ID 的标签计数刷新请求");
            return;
        }
        String normalized = noteId.trim();
        if (!pending.add(normalized)) {
            log.debug("标签计数刷新已在队列中：noteId={}", normalized);
            return;
        }
        log.debug("排队刷新标签计数：noteId={}", normalized);
        try {
            worker.schedule(() -> refresh(normalized), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            pending.remove(normalized);
            log.error("标签计数刷新线程已停止，忽略待处理笔记：{}", normalized, e);
        }
    }

    private void refresh(String noteId) {
        pending.remove(noteId);
        try {
            tagsService.invalidateUsage(noteId);
            tagsService.refreshOccurrences(noteId);
            log.debug("标签计数刷新完成：noteId={}", noteId);
        } catch (Exception e) {
            log.error("标签计数异步刷新失败：noteId={}", noteId, e);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("标签计数刷新服务正在停止：pending={}", pending.size());
        worker.shutdownNow();
        pending.clear();
    }
}
