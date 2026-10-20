package com.thisnote.ext.aisummary.service;

import com.thisnote.ext.aisummary.client.SummaryAiClient;
import com.thisnote.ext.aisummary.repository.AiSummaryRepository;
import com.thisnote.plugin.api.event.GraphifyNoteContext;
import com.thisnote.plugin.api.event.GraphifyNoteContextFutureEvent;
import com.thisnote.plugin.api.event.NoteContentFutureEvent;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class AiSummaryService {

    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final AiSummaryRepository repository;
    private final AiSummarySettingsService settingsService;
    private final SummaryAiClient aiClient;
    private final ExecutorService worker;
    private final Map<String, PendingTask> pending = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private final Map<String, Long> revisions = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private ApplicationEventPublisher eventPublisher;

    public AiSummaryService(AiSummaryRepository repository,
                            AiSummarySettingsService settingsService,
                            SummaryAiClient aiClient) {
        this.repository = repository;
        this.settingsService = settingsService;
        this.aiClient = aiClient;
        this.worker = new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                task -> {
                    Thread thread = new Thread(task, "thisnote-ai-summary");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public SummaryView getOne(String noteId) {
        String normalizedNoteId = normalizeNoteId(noteId);
        return view(normalizedNoteId);
    }

    public SummaryView generate(String noteId,
                                boolean force,
                                boolean automatic) {
        String normalizedNoteId = normalizeNoteId(noteId);
        if (automatic && !settingsService.autoEnabled()) {
            log.info("AI 总结自动生成已关闭，跳过：noteId={}", normalizedNoteId);
            return view(normalizedNoteId);
        }
        String profileId = settingsService.requireAiProfileId();
        AiSummaryRepository.StoredSummary stored = repository.getOne(normalizedNoteId);
        PendingTask existing = pending.get(normalizedNoteId);
        if (existing != null) {
            log.debug("AI 总结任务已存在：noteId={}, force={}", normalizedNoteId, force);
            return view(normalizedNoteId);
        }
        if (!force && stored != null && !stored.stale()) {
            log.debug("AI 总结缓存仍有效：noteId={}", normalizedNoteId);
            return view(normalizedNoteId);
        }

        PendingTask task = new PendingTask(
                generation.get(),
                revisions.getOrDefault(normalizedNoteId, 0L)
        );
        if (pending.putIfAbsent(normalizedNoteId, task) != null) {
            log.debug("AI 总结任务并发已存在：noteId={}", normalizedNoteId);
            return view(normalizedNoteId);
        }
        errors.remove(normalizedNoteId);
        log.info("AI 总结任务已排队：noteId={}, force={}, automatic={}, profileId={}",
                normalizedNoteId, force, automatic, profileId);
        try {
            worker.execute(() -> runTask(normalizedNoteId, profileId, task));
        } catch (RejectedExecutionException e) {
            pending.remove(normalizedNoteId, task);
            errors.put(normalizedNoteId, "AI 总结任务队列已满，请稍后重试");
            log.error("AI 总结任务无法启动：noteId={}", normalizedNoteId, e);
        }
        return view(normalizedNoteId);
    }

    public void resetAll() {
        generation.incrementAndGet();
        revisions.clear();
        pending.clear();
        errors.clear();
        repository.deleteAll();
        log.info("AI 总结状态已重置");
    }

    public void close() {
        generation.incrementAndGet();
        pending.clear();
        worker.shutdownNow();
        log.info("AI 总结线程池已关闭");
    }

    public void invalidateNote(String noteId) {
        String normalizedNoteId = normalizeNoteId(noteId);
        revisions.merge(normalizedNoteId, 1L, Long::sum);
        if (repository.updateStale(normalizedNoteId)) {
            log.info("AI 总结已失效：noteId={}", normalizedNoteId);
        }
    }

    public void deleteNote(String noteId) {
        String normalizedNoteId = normalizeNoteId(noteId);
        revisions.merge(normalizedNoteId, 1L, Long::sum);
        errors.remove(normalizedNoteId);
        if (repository.deleteOne(normalizedNoteId)) {
            log.info("笔记已删除，AI 总结一并清除：noteId={}", normalizedNoteId);
        }
    }

    public int invalidateAll() {
        generation.incrementAndGet();
        errors.clear();
        int changed = repository.updateAllStale();
        log.info("AI 总结全部失效：marked={}", changed);
        return changed;
    }

    private void runTask(String noteId,
                         String profileId,
                         PendingTask task) {
        try {
            NoteCardDTO content = noteContent(noteId);
            if (content == null || content.note() == null) {
                throw new IllegalArgumentException("笔记不存在");
            }
            GraphifyNoteContext context = graphContext(noteId);
            if (!context.available()) {
                throw new IllegalStateException("Graphify 尚未生成可用的知识图谱，请先完成索引");
            }
            String summary = aiClient.generate(profileId, content, context);
            if (!isCurrent(noteId, task)) {
                log.info("AI 总结结果已丢弃：生成期间状态已变化，noteId={}", noteId);
                return;
            }
            String generatedAt = Instant.now().toString();
            repository.update(noteId, summary, generatedAt);
            errors.remove(noteId);
            log.info("AI 总结生成完成：noteId={}, chars={}, concepts={}, related={}",
                    noteId, summary.length(), context.concepts().size(), context.relatedNotes().size());
        } catch (Exception e) {
            if (isCurrent(noteId, task)) {
                errors.put(noteId, safeMessage(e));
            }
            log.error("AI 总结生成失败：noteId={}", noteId, e);
        } finally {
            pending.remove(noteId, task);
        }
    }

    private NoteCardDTO noteContent(String noteId) {
        NoteContentFutureEvent request = new NoteContentFutureEvent(noteId, new CompletableFuture<>());
        publisher().publishEvent(request);
        return request.result().orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private GraphifyNoteContext graphContext(String noteId) {
        GraphifyNoteContextFutureEvent request = GraphifyNoteContextFutureEvent.request(noteId);
        publisher().publishEvent(request);
        return request.result().orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private SummaryView view(String noteId) {
        AiSummaryRepository.StoredSummary stored = repository.getOne(noteId);
        return new SummaryView(
                noteId,
                stored == null ? "" : stored.summary(),
                stored == null ? "" : stored.generatedAt(),
                stored != null && stored.stale(),
                pending.containsKey(noteId),
                settingsService.autoEnabled(),
                errors.getOrDefault(noteId, "")
        );
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("主容器消息管道尚未就绪");
        }
        return eventPublisher;
    }

    private static String normalizeNoteId(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        return noteId.trim();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "生成失败" : message;
    }

    private boolean isCurrent(String noteId, PendingTask task) {
        return task.generation() == generation.get()
                && task.revision() == revisions.getOrDefault(noteId, 0L);
    }

    private record PendingTask(long generation, long revision) {
    }

    public record SummaryView(String noteId,
                              String summary,
                              String generatedAt,
                              boolean stale,
                              boolean pending,
                              boolean autoEnabled,
                              String error) {
    }
}
