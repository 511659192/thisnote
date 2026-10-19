package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.client.TagsNotesClient;
import com.thisnote.plugin.api.event.NoteTagSuggestionEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class AutoTagService {

    private static final int MAX_AUTO_TAGS = 20;

    private final TagsNotesClient notesClient;
    private final TagsService tagsService;
    private ApplicationEventPublisher eventPublisher;

    public AutoTagService(TagsNotesClient notesClient,
                          TagsService tagsService) {
        this.notesClient = notesClient;
        this.tagsService = tagsService;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public List<String> extract(String noteId) {
        NoteDTO note = notesClient.note(noteId);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在");
        }
        List<CardDTO> cards = notesClient.cards(noteId);
        List<String> documents = new ArrayList<>();
        if (cards != null) {
            for (CardDTO card : cards) {
                if (card != null && card.content() != null) {
                    documents.add(card.content());
                }
            }
        }
        Set<String> suggestions = new LinkedHashSet<>();
        NoteTagSuggestionEvent request = NoteTagSuggestionEvent.request(noteId, MAX_AUTO_TAGS);
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(request);
                if (request.suggestions().isDone()) {
                    suggestions.addAll(request.suggestions().getNow(List.of()));
                }
            } catch (RuntimeException e) {
                log.debug("AI 标签建议跳过：noteId={}, errorType={}", noteId, e.getClass().getSimpleName());
            }
        }
        suggestions.addAll(HighLevelTagExtractor.extract(documents, MAX_AUTO_TAGS));
        return tagsService.mergeUpTo(noteId, new ArrayList<>(suggestions), MAX_AUTO_TAGS);
    }
}
