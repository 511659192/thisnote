package com.thisnote.ext.tags.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
            return;
        }
        String normalized = noteId.trim();
        tagsService.invalidateUsage(normalized);
        if (!pending.add(normalized)) {
            return;
        }
        try {
            worker.schedule(() -> refresh(normalized), DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            pending.remove(normalized);
            log.debug("标签计数刷新线程已停止，忽略待处理笔记：{}", normalized);
        }
    }

    private void refresh(String noteId) {
        pending.remove(noteId);
        try {
            tagsService.refreshOccurrences(noteId);
        } catch (RuntimeException e) {
            log.warn("标签计数异步刷新失败：noteId={}, errorType={}",
                    noteId, e.getClass().getSimpleName());
        }
    }

    @PreDestroy
    public void stop() {
        worker.shutdownNow();
        pending.clear();
    }
}
