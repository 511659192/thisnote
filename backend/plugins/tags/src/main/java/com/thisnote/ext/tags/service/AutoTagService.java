package com.thisnote.ext.tags.service;

import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AutoTagService {

    private static final int MAX_AUTO_TAGS = 20;
    private static final long DEADLINE_GRACE_MILLIS = 5_000;

    private final TagsService tagsService;
    private final TagSuggestionService tagSuggestionService;
    private final ExecutorService aiWorker;
    private final Map<String, PendingTask> pending = new ConcurrentHashMap<>();

    @Autowired
    public AutoTagService(TagsService tagsService,
                          TagSuggestionService tagSuggestionService) {
        this(tagsService, tagSuggestionService, defaultExecutor());
    }

    AutoTagService(TagsService tagsService,
                   TagSuggestionService tagSuggestionService,
                   ExecutorService aiWorker) {
        this.tagsService = tagsService;
        this.tagSuggestionService = tagSuggestionService;
        this.aiWorker = aiWorker;
    }

    public ExtractResult extract(String noteId) {
        NoteCardDTO content = tagsService.noteContent(noteId);
        if (content == null || content.note() == null) {
            throw new IllegalArgumentException("笔记不存在");
        }
        NoteDTO note = content.note();
        List<CardDTO> cards = content.cards() == null ? List.of() : content.cards();
        List<String> documents = cards.stream()
                                      .filter(Objects::nonNull)
                                      .map(CardDTO::content)
                                      .filter(Objects::nonNull)
                                      .toList();
        Set<String> suggestions = new LinkedHashSet<>(HighLevelTagExtractor.extract(documents, MAX_AUTO_TAGS));
        List<String> tags = tagsService.mergeUpTo(
                noteId,
                new ArrayList<>(suggestions),
                MAX_AUTO_TAGS,
                TagTextCounter.noteText(note, cards)
        );
        log.debug("自动标签本地候选完成：noteId={}, tags={}", noteId, tags.size());
        long deadlineEpochMs = scheduleAi(note, cards);
        return new ExtractResult(noteId.trim(), tags, deadlineEpochMs > 0, deadlineEpochMs);
    }

    public ExtractStatus status(String noteId) {
        String normalizedNoteId = noteId.trim();
        PendingTask task = pending.get(normalizedNoteId);
        return new ExtractStatus(
                normalizedNoteId,
                tagsService.noteTags(normalizedNoteId),
                task != null,
                task == null ? 0 : task.deadlineEpochMs()
        );
    }

    private long scheduleAi(NoteDTO note,
                            List<CardDTO> cards) {
        String noteId = note.noteId();
        PendingTask existing = pending.get(noteId);
        if (existing != null) {
            log.debug("AI 标签任务已存在：noteId={}", noteId);
            return existing.deadlineEpochMs();
        }
        long deadlineEpochMs = System.currentTimeMillis()
                + TagSuggestionService.RESULT_TIMEOUT_SECONDS * 1_000
                + DEADLINE_GRACE_MILLIS;
        PendingTask task = new PendingTask(new CompletableFuture<>(), deadlineEpochMs);
        if (pending.putIfAbsent(noteId, task) != null) {
            PendingTask concurrent = pending.get(noteId);
            log.debug("AI 标签任务并发已存在：noteId={}", noteId);
            return concurrent == null ? 0 : concurrent.deadlineEpochMs();
        }
        log.debug("AI 标签任务已排队：noteId={}, deadlineEpochMs={}", noteId, deadlineEpochMs);
        try {
            aiWorker.execute(() -> {
                try {
                    List<String> suggestions = tagSuggestionService.suggest(note, cards, MAX_AUTO_TAGS);
                    List<String> merged = suggestions.isEmpty()
                            ? List.of()
                            : tagsService.mergeUpTo(noteId, suggestions, MAX_AUTO_TAGS,
                            TagTextCounter.noteText(note, cards));
                    log.debug("AI 标签任务完成：noteId={}, suggestions={}, mergedTags={}",
                            noteId, suggestions.size(), merged.size());
                } catch (Exception e) {
                    log.error("AI 标签后台任务失败：noteId={}", noteId, e);
                } finally {
                    pending.remove(noteId, task);
                    task.completion().complete(null);
                }
            });
        } catch (RuntimeException e) {
            pending.remove(noteId, task);
            log.error("AI 标签后台任务无法启动：noteId={}", noteId, e);
            return 0;
        }
        return deadlineEpochMs;
    }

    @PreDestroy
    public void close() {
        aiWorker.shutdownNow();
    }

    private static ExecutorService defaultExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                task -> {
                    Thread thread = new Thread(task, "thisnote-tags-ai");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    public record ExtractResult(String noteId,
                                List<String> tags,
                                boolean pending,
                                long deadlineEpochMs) {
    }

    public record ExtractStatus(String noteId,
                                List<String> tags,
                                boolean pending,
                                long deadlineEpochMs) {
    }

    private record PendingTask(CompletableFuture<Void> completion,
                               long deadlineEpochMs) {
    }
}
