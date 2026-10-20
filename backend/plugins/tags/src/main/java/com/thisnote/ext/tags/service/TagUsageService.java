package com.thisnote.ext.tags.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class TagUsageService {

    private final TagsService tagsService;

    public TagUsageService(TagsService tagsService) {
        this.tagsService = tagsService;
    }

    public Map<String, Integer> occurrences() {
        try {
            if (tagsService.hasUninitializedUsage()) {
                tagsService.refreshUninitializedUsage();
            }
            return tagsService.usageCounts();
        } catch (Exception e) {
            log.error("标签计数缓存补算失败，回退实时统计", e);
            return calculateRealtime(tagsService.usageCounts());
        }
    }

    private Map<String, Integer> calculateRealtime(Map<String, Integer> knownTags) {
        Set<String> tags = new LinkedHashSet<>(knownTags.keySet());
        if (tags.isEmpty()) {
            return knownTags;
        }
        String corpus = tagsService.allNotes().stream()
                .filter(note -> note != null && note.noteId() != null && !note.noteId().isBlank())
                .map(note -> TagTextCounter.noteText(note, tagsService.cards(note.noteId())) + "\n")
                .collect(Collectors.joining());
        Map<String, Integer> result = new LinkedHashMap<>(knownTags);
        result.putAll(TagTextCounter.counts(corpus, tags));
        return result;
    }
}
