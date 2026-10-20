package com.thisnote.ext.tags.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class TagRules {

    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\([^)]*\\)");
    private static final Pattern LEADING_ORDER = Pattern.compile("^\\s*(?:\\d+[.)、]|[一二三四五六七八九十]+[、.)]|[-*+])\\s*");
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*[（(][^）)]{0,80}[）)]\\s*$");
    private static final Pattern FILE_EXTENSION = Pattern.compile(
            ".*\\.(?:java|md|json|yml|yaml|xml|ts|tsx|js|jsx|css|html|properties)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMEL_CASE = Pattern.compile("[a-z][A-Z]");
    private static final Pattern SNAKE_CASE = Pattern.compile("^[a-z][a-z0-9]*(?:_[a-z0-9]+)+$");
    private static final Pattern CODE_TAIL = Pattern.compile("(?:Service|Client|Controller|Repository|Plugin|Config|Exception|Listener)$");
    private static final Pattern PATH_OR_CODE = Pattern.compile("[/\\\\`()\\[\\]{}<>=|$]");
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[，,、;；:：。.!?！？]");
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

    private TagRules() {
    }

    static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        if (SNAKE_CASE.matcher(raw.trim()).matches()) {
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
        value = value.replaceAll("^#+\\s*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("[：:，,。.;；!?！？]+$", "")
                .trim();
        return isMeaningful(value) ? value : null;
    }

    static boolean isMeaningful(String value) {
        if (value.length() < 2 || value.length() > 24 || GENERIC.contains(value.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (HASH_OR_NUMBER.matcher(value).matches() || FILE_EXTENSION.matcher(value).matches()) {
            return false;
        }
        if (CAMEL_CASE.matcher(value).find() || SNAKE_CASE.matcher(value).matches() || CODE_TAIL.matcher(value).find()) {
            return false;
        }
        if (PATH_OR_CODE.matcher(value).find() || SENTENCE_PUNCTUATION.matcher(value).find()) {
            return false;
        }
        return !value.startsWith("Community ") && value.split("\\s+").length <= 8;
    }

    static List<String> splitPhrases(String value) {
        return List.of(PHRASE_BREAK.split(value));
    }
}
