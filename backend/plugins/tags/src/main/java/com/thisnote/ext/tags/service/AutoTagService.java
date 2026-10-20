package com.thisnote.ext.tags.service;

import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
@Slf4j
public class AutoTagService {

    private static final int MAX_AUTO_TAGS = 20;

    private final TagsService tagsService;
    private final TagSuggestionService tagSuggestionService;
    private final ExecutorService aiWorker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "thisnote-tags-ai");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, CompletableFuture<Void>> pending = new ConcurrentHashMap<>();

    public AutoTagService(TagsService tagsService,
                          TagSuggestionService tagSuggestionService) {
        this.tagsService = tagsService;
        this.tagSuggestionService = tagSuggestionService;
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
        scheduleAi(note, cards);
        return new ExtractResult(noteId.trim(), tags, pending.containsKey(noteId.trim()));
    }

    public ExtractStatus status(String noteId) {
        String normalizedNoteId = noteId.trim();
        return new ExtractStatus(normalizedNoteId, tagsService.noteTags(normalizedNoteId), pending.containsKey(normalizedNoteId));
    }

    private void scheduleAi(NoteDTO note,
                            List<CardDTO> cards) {
        String noteId = note.noteId();
        if (pending.containsKey(noteId)) {
            return;
        }
        CompletableFuture<Void> task = new CompletableFuture<>();
        if (pending.putIfAbsent(noteId, task) != null) {
            return;
        }
        try {
            aiWorker.execute(() -> {
                try {
                    List<String> suggestions = tagSuggestionService.suggest(note, cards, MAX_AUTO_TAGS);
                    if (!suggestions.isEmpty()) {
                        tagsService.mergeUpTo(noteId, suggestions, MAX_AUTO_TAGS, TagTextCounter.noteText(note, cards));
                    }
                } catch (Exception e) {
                    log.error("AI 标签后台任务失败：noteId={}", noteId, e);
                } finally {
                    pending.remove(noteId, task);
                    task.complete(null);
                }
            });
        } catch (RuntimeException e) {
            pending.remove(noteId, task);
            log.error("AI 标签后台任务无法启动：noteId={}", noteId, e);
        }
    }

    @PreDestroy
    public void close() {
        aiWorker.shutdownNow();
    }

    public record ExtractResult(String noteId, List<String> tags, boolean pending) {
    }

    public record ExtractStatus(String noteId, List<String> tags, boolean pending) {
    }
}
