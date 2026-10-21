package com.thisnote.ext.aititle.support;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class TitleHeuristics {

    private static final Pattern UUID = Pattern.compile(
            "(?i)^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$");
    private static final Pattern HASH = Pattern.compile(
            "(?i)^(?:[0-9a-f]{16,}|[a-z0-9]{24,})$");
    private static final Pattern GENERIC = Pattern.compile(
            "(?i)^(?:paste(?:d)?[-_ ]?(?:image|note)?|image|img|photo|picture|screenshot|screen[-_ ]?shot|scan|document|file|untitled|new[-_ ]?note|图片|截图|屏幕截图|粘贴图片|未命名|新笔记|扫描件|照片)(?:[-_ .]?\\d+)?$");
    private static final Pattern CAPTURE_FILE = Pattern.compile(
            "(?i)^(?:img|dsc|pxl|screenshot|screen[-_ ]?shot|scan|wechatimg|mmexport|微信图片|qq图片|屏幕截图)[-_ ]?\\d+.*$");
    private static final Pattern NUMBER_ONLY = Pattern.compile("^\\d+$");
    private static final Pattern PATH = Pattern.compile("[/\\\\]");
    private static final Pattern TITLE_PREFIX = Pattern.compile("^(?:标题|title)\\s*[:：]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("^\\s*(?:#{1,6}|[-*•]|\\d+[.、)])\\s*");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[。；;，,：:]+$");
    private static final Pattern FILE_EXTENSION = Pattern.compile(
            "(?i)\\.(?:md|png|jpe?g|webp)$");

    private TitleHeuristics() {
    }

    public static boolean isMeaningful(String title) {
        if (title == null) {
            return false;
        }
        String value = title.trim();
        if (value.length() < 2 || PATH.matcher(value).find()) {
            return false;
        }
        return !UUID.matcher(value).matches()
                && !HASH.matcher(value).matches()
                && !NUMBER_ONLY.matcher(value).matches()
                && !GENERIC.matcher(value).matches()
                && !CAPTURE_FILE.matcher(value).matches()
                && value.matches(".*[\\p{L}\\p{N}].*");
    }

    public static List<String> normalizeCandidates(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        response.lines()
                .map(TitleHeuristics::normalizeGenerated)
                .filter(TitleHeuristics::isMeaningful)
                .forEach(candidates::add);
        return candidates.stream().limit(3).toList();
    }

    public static String normalizeGenerated(String response) {
        if (response == null || response.isBlank()) {
            return "";
        }
        String title = response.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
        title = TITLE_PREFIX.matcher(title).replaceFirst("");
        title = MARKDOWN_PREFIX.matcher(title).replaceFirst("");
        title = title.replaceAll("^[\\s\"'“”‘’`]+|[\\s\"'“”‘’`]+$", "");
        title = FILE_EXTENSION.matcher(title).replaceFirst("");
        title = TRAILING_PUNCTUATION.matcher(title).replaceFirst("");
        title = title.replaceAll("\\s+", " ").trim();
        return title.length() <= 40 ? title : title.substring(0, 40).trim();
    }
}
