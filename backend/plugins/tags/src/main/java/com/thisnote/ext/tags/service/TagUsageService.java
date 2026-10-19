package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.client.TagsNotesClient;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class TagUsageService {

    private final TagsService tagsService;
    private final TagsNotesClient notesClient;

    public TagUsageService(TagsService tagsService,
                           TagsNotesClient notesClient) {
        this.tagsService = tagsService;
        this.notesClient = notesClient;
    }

    public Map<String, Integer> occurrences() {
        try {
            if (tagsService.hasUninitializedUsage()) {
                tagsService.refreshUninitializedUsage();
            }
            return tagsService.usageCounts();
        } catch (RuntimeException e) {
            log.warn("标签计数缓存补算失败，回退实时统计：errorType={}", e.getClass().getSimpleName());
            return calculateRealtime(tagsService.usageCounts());
        }
    }

    private Map<String, Integer> calculateRealtime(Map<String, Integer> knownTags) {
        Set<String> tags = new LinkedHashSet<>(knownTags.keySet());
        if (tags.isEmpty()) {
            return knownTags;
        }
        StringBuilder corpus = new StringBuilder();
        for (NoteDTO note : notesClient.allNotes()) {
            if (note == null || note.noteId() == null || note.noteId().isBlank()) {
                continue;
            }
            corpus.append(TagTextCounter.noteText(note, notesClient.cards(note.noteId())))
                    .append('\n');
        }
        Map<String, Integer> result = new LinkedHashMap<>(knownTags);
        result.putAll(TagTextCounter.counts(corpus.toString(), tags));
        return result;
    }
}
