package com.thisnode.ext.notes.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

public final class NoteTitleDefaults {

    private static final String PLACEHOLDER_PREFIX = "请输入标题-";
    private static final DateTimeFormatter TITLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "^请输入标题-\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(?:-\\d+)?$");

    private NoteTitleDefaults() {
    }

    public static String currentTimeTitle() {
        return LocalDateTime.now().format(TITLE_TIME_FORMAT);
    }

    public static String placeholderTitle() {
        return PLACEHOLDER_PREFIX + currentTimeTitle();
    }

    public static boolean isPlaceholderTitle(String title) {
        return title != null && PLACEHOLDER_PATTERN.matcher(title).matches();
    }

    /** First non-blank line of the card content, with surrounding whitespace removed. */
    public static String firstLine(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        return content.strip().lines().findFirst().orElse("").strip();
    }
}
