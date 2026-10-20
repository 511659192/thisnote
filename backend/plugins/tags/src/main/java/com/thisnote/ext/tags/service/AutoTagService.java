package com.thisnote.ext.tags.service;

import com.thisnote.plugin.api.event.NoteTagSuggestionEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
public class AutoTagService {

    private static final int MAX_AUTO_TAGS = 20;

    private final TagsService tagsService;
    private ApplicationEventPublisher eventPublisher;

    public AutoTagService(TagsService tagsService) {
        this.tagsService = tagsService;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public List<String> extract(String noteId) {
        NoteDTO note = tagsService.note(noteId);
        if (note == null) {
            throw new IllegalArgumentException("笔记不存在");
        }
        List<CardDTO> cards = tagsService.cards(noteId);
        List<String> documents = cards == null ? List.of() : cards.stream()
                                                             .filter(Objects::nonNull)
                                                             .map(CardDTO::content)
                                                             .filter(Objects::nonNull)
                                                             .toList();
        Set<String> suggestions = new LinkedHashSet<>();
        NoteTagSuggestionEvent request = NoteTagSuggestionEvent.request(noteId, MAX_AUTO_TAGS);
        if (eventPublisher != null) {
            try {
                eventPublisher.publishEvent(request);
                if (request.suggestions().isDone()) {
                    suggestions.addAll(request.suggestions().getNow(List.of()));
                }
            } catch (Exception e) {
                log.error("AI 标签建议跳过：noteId={}", noteId, e);
            }
        }
        suggestions.addAll(HighLevelTagExtractor.extract(documents, MAX_AUTO_TAGS));
        return tagsService.mergeUpTo(
                noteId,
                new ArrayList<>(suggestions),
                MAX_AUTO_TAGS,
                TagTextCounter.noteText(note, cards)
        );
    }
}
