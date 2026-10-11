package com.thisnode.ext.notes.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown title, preview, and filesystem-safe slug helpers. */
public final class MarkdownUtils {

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");

    private MarkdownUtils() {
    }

    public static String firstHeading(String contentMd) {
        if (contentMd == null) {
            return null;
        }
        Matcher matcher = HEADING.matcher(FENCED_CODE.matcher(contentMd).replaceAll(" "));
        if (matcher.find()) {
            String heading = matcher.group(1).trim();
            return heading.isEmpty() ? null : heading;
        }
        return null;
    }


    /** Filesystem-safe slug that keeps readable UTF-8 characters. */
    public static String slug(String title) {
        if (title == null || title.isBlank()) {
            return "untitled";
        }
        String slug = title.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "-")
                .replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.\\-]+|[.\\-]+$", "");
        if (slug.isBlank()) {
            slug = "untitled";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
}
