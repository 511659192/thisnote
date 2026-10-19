package com.thisnote.ext.tags.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class HighLevelTagExtractor {

    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern LEADING_ORDER = Pattern.compile("^\\s*(?:\\d+[.)、]|[一二三四五六七八九十]+[、.)]|[-*+])\\s*");
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*[（(][^）)]{0,80}[）)]\\s*$");
    private static final Pattern FILE_EXTENSION = Pattern.compile(
            ".*\\.(?:java|md|json|yml|yaml|xml|ts|tsx|js|jsx|css|html|properties)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CODE_TAIL = Pattern.compile(
            "(?:Service|Client|Controller|Repository|Plugin|Config|Exception|Listener)$");
    private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)+$");
    private static final Pattern PHRASE_BREAK = Pattern.compile("[、,，;；:：]");
    private static final Pattern HASH_OR_NUMBER = Pattern.compile(
            "^(?:\\d+|[0-9a-f]{8,}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> GENERIC = Set.of(
            "overview", "introduction", "background", "summary", "conclusion", "contents",
            "references", "appendix", "notes", "example", "examples", "usage", "installation",
            "configuration", "setup", "misc", "other", "项目是什么", "仓库结构", "总体架构",
            "后端", "界面侧", "构建与交付", "运行与构建", "测试与验证现状", "文档缺口", "简介",
            "概述", "背景", "总结", "结论", "目录", "参考", "参考资料", "附录", "示例", "例子",
            "说明", "注意事项", "其他", "真实性", "开工前", "需求与决策", "代码修改",
            "验证与停止", "经验沉淀", "交付", "思考强度偏好", "总原则", "工作规范"
    );

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
                    for (String part : PHRASE_BREAK.split(value)) {
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
        String label = clean(raw);
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

    private static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = LINK.matcher(raw).replaceAll("$1")
                .replaceAll("<[^>]+>", "")
                .replace("`", "")
                .replace("*", "")
                .replace("_", " ")
                .replace("~", "")
                .replace("“", "")
                .replace("”", "")
                .replace("\"", "")
                .trim();
        value = LEADING_ORDER.matcher(value).replaceFirst("");
        value = TRAILING_PAREN.matcher(value).replaceFirst("");
        value = value.replaceAll("\\s+", " ")
                .replaceAll("[：:，,。.;；!?！？]+$", "")
                .trim();
        if (!keep(value)) {
            return null;
        }
        return value;
    }

    private static boolean keep(String value) {
        if (value.length() < 2 || value.length() > 24) {
            return false;
        }
        if (GENERIC.contains(value.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (HASH_OR_NUMBER.matcher(value).matches() || FILE_EXTENSION.matcher(value).matches()) {
            return false;
        }
        if (value.contains("://")
                || value.contains("\\")
                || value.contains("::")
                || value.contains("=>")
                || value.contains("{")
                || value.contains("}")
                || value.contains("(")
                || value.contains(")")
                || value.contains("[")
                || value.contains("]")
                || value.contains("<")
                || value.contains(">")
                || value.contains("=")
                || value.contains("|")
                || value.contains("$")) {
            return false;
        }
        if (PHRASE_BREAK.matcher(value).find()) {
            return false;
        }
        if (value.contains("/") && (value.startsWith("/") || value.contains(".") || value.split("/").length > 2)) {
            return false;
        }
        if (SNAKE_CASE.matcher(value).matches() || CODE_TAIL.matcher(value).find()) {
            return false;
        }
        if (value.startsWith("Community ") || value.split("\\s+").length > 8) {
            return false;
        }
        return true;
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
