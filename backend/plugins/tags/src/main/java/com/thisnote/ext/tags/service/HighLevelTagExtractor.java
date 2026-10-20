package com.thisnote.ext.tags.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HighLevelTagExtractor {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private HighLevelTagExtractor() {
    }

    static List<String> extract(List<String> documents, int limit) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        int order = 0;
        for (String document : documents) {
            if (document == null || document.isBlank()) {
                continue;
            }
            boolean firstLine = true;
            for (String line : document.lines().toList()) {
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    int level = heading.group(1).length();
                    if (level > 1) {
                        order = add(candidates, heading.group(2), Math.max(2, 8 - level), order);
                    }
                    firstLine = false;
                    continue;
                }
                Matcher bold = BOLD.matcher(line);
                while (bold.find()) {
                    String value = bold.group(1) != null ? bold.group(1) : bold.group(2);
                    for (String part : TagRules.splitPhrases(value)) {
                        order = add(candidates, part, 3, order);
                    }
                }
                if (firstLine && !line.isBlank()) {
                    order = add(candidates, line, 1, order);
                }
                firstLine = false;
            }
        }
        return candidates.values().stream()
                .sorted((left, right) -> Integer.compare(right.score, left.score) != 0
                        ? Integer.compare(right.score, left.score)
                        : Integer.compare(left.order, right.order))
                .limit(Math.max(0, limit))
                .map(Candidate::label)
                .toList();
    }

    private static int add(Map<String, Candidate> candidates, String raw, int weight, int order) {
        String label = TagRules.clean(raw);
        if (label == null) {
            return order;
        }
        String key = label.toLowerCase(Locale.ROOT);
        Candidate existing = candidates.get(key);
        if (existing == null) {
            candidates.put(key, new Candidate(label, weight, order));
            return order + 1;
        }
        existing.score += weight;
        return order;
    }

    private static final class Candidate {
        private final String label;
        private int score;
        private final int order;

        private Candidate(String label, int score, int order) {
            this.label = label;
            this.score = score;
            this.order = order;
        }

        private String label() {
            return label;
        }
    }
}
