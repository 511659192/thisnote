package com.thisnote.ext.tags.service;

import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class TagTextCounter {

    private TagTextCounter() {
    }

    static String noteText(NoteDTO note,
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

    static Map<String, Integer> counts(String text,
                                       Iterable<String> tags) {
        String haystack = text.toLowerCase(Locale.ROOT);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String tag : tags) {
            String needle = tag.toLowerCase(Locale.ROOT);
            int count = count(haystack, needle, isAsciiWord(tag));
            if (count > 0) {
                counts.put(tag, count);
            }
        }
        return counts;
    }

    private static boolean isAsciiWord(String tag) {
        return tag.chars().allMatch(value -> value < 128)
                && tag.chars().anyMatch(Character::isLetterOrDigit);
    }

    private static int count(String text,
                             String needle,
                             boolean asciiWord) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (from < text.length()) {
            int index = text.indexOf(needle, from);
            if (index < 0) {
                break;
            }
            if (!asciiWord || isWordBoundary(text, index, index + needle.length())) {
                count++;
            }
            from = index + needle.length();
        }
        return count;
    }

    private static boolean isWordBoundary(String text,
                                          int start,
                                          int end) {
        int before = start == 0 ? -1 : text.codePointBefore(start);
        int after = end >= text.length() ? -1 : text.codePointAt(end);
        return !isWordCodePoint(before) && !isWordCodePoint(after);
    }

    private static boolean isWordCodePoint(int codePoint) {
        return codePoint >= 0
                && (codePoint == '_' || Character.isLetterOrDigit(codePoint));
    }
}
