package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.client.TagsNotesClient;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TagUsageService {

    private final TagsService tagsService;
    private final TagsNotesClient notesClient;

    public TagUsageService(TagsService tagsService,
                           TagsNotesClient notesClient) {
        this.tagsService = tagsService;
        this.notesClient = notesClient;
    }

    public Map<String, Integer> occurrences() {
        Set<String> tags = new LinkedHashSet<>();
        tagsService.all().values().forEach(tags::addAll);
        Map<String, Integer> counts = new LinkedHashMap<>();
        tags.forEach(tag -> counts.put(tag, 0));
        if (tags.isEmpty()) {
            return counts;
        }

        for (NoteDTO note : notesClient.allNotes()) {
            if (note == null || note.noteId() == null || note.noteId().isBlank()) {
                continue;
            }
            String text = noteText(note, notesClient.cards(note.noteId()));
            for (String tag : tags) {
                int count = count(text, tag);
                if (count > 0) {
                    counts.merge(tag, count, Integer::sum);
                }
            }
        }
        return counts;
    }

    private static String noteText(NoteDTO note,
                                   List<CardDTO> cards) {
        StringBuilder text = new StringBuilder(note.title() == null ? "" : note.title());
        if (cards != null) {
            for (CardDTO card : cards) {
                if (card != null && card.content() != null) {
                    text.append('\n').append(card.content());
                }
            }
        }
        return text.toString();
    }

    private static int count(String text,
                             String tag) {
        boolean asciiWord = tag.chars().allMatch(value -> value < 128)
                && tag.chars().anyMatch(Character::isLetterOrDigit);
        String expression = asciiWord
                ? "(?<![\\p{L}\\p{N}_])" + Pattern.quote(tag) + "(?![\\p{L}\\p{N}_])"
                : Pattern.quote(tag);
        Matcher matcher = Pattern.compile(expression,
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                .matcher(text);
        int result = 0;
        while (matcher.find()) {
            result++;
        }
        return result;
    }
}
