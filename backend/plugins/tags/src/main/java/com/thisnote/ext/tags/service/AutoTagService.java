package com.thisnote.ext.tags.service;

import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class AutoTagService {

    private static final int MAX_AUTO_TAGS = 20;

    private final TagsService tagsService;
    private final TagSuggestionService tagSuggestionService;

    public AutoTagService(TagsService tagsService,
                          TagSuggestionService tagSuggestionService) {
        this.tagsService = tagsService;
        this.tagSuggestionService = tagSuggestionService;
    }

    public List<String> extract(String noteId) {
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
        Set<String> suggestions = new LinkedHashSet<>(tagSuggestionService.suggest(note, cards, MAX_AUTO_TAGS));
        suggestions.addAll(HighLevelTagExtractor.extract(documents, MAX_AUTO_TAGS));
        return tagsService.mergeUpTo(
                noteId,
                new ArrayList<>(suggestions),
                MAX_AUTO_TAGS,
                TagTextCounter.noteText(note, cards)
        );
    }
}
