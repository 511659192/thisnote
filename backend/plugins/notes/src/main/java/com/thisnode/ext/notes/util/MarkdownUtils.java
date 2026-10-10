package com.thisnode.ext.notes.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown helpers: wiki-link and inline-tag extraction, title normalization, and filesystem-safe slugs.
 */
public final class MarkdownUtils {

    /** {@code [[Target]]}, {@code [[Target|Label]]}, {@code [[Target#Heading]]}. */
    private static final Pattern WIKI_LINK = Pattern.compile(
            "\\[\\[\\s*([^\\[\\]|#]+?)\\s*(?:#([^\\[\\]|]*))?\\s*(?:\\|([^\\[\\]]*))?\\s*\\]\\]");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern TAG_TOKEN = Pattern.compile("(?:^|\\s)#([\\p{L}\\p{N}_/-]{1,64})");

    private MarkdownUtils() {
    }

    public record WikiLink(String target, String label, String heading) {
    }

    /** Extract wiki links, ignoring fenced/inline code so examples are not treated as links. */
    public static List<WikiLink> wikiLinks(String contentMd) {
        List<WikiLink> found = new ArrayList<>();
        if (contentMd == null || contentMd.isEmpty()) {
            return found;
        }
        String stripped = INLINE_CODE.matcher(FENCED_CODE.matcher(contentMd).replaceAll(" ")).replaceAll(" ");
        Matcher m = WIKI_LINK.matcher(stripped);
        while (m.find()) {
            String target = m.group(1) == null ? "" : m.group(1).trim();
            if (target.isEmpty()) {
                continue;
            }
            String heading = m.group(2) == null ? null : m.group(2).trim();
            String label = m.group(3) == null ? null : m.group(3).trim();
            found.add(new WikiLink(target, label, heading));
        }
        return found;
    }

    /** Titles/aliases referenced by {@code [[...]]} but not yet existing as nodes. */
    public static List<String> unresolvedLinks(String contentMd) {
        List<String> out = new ArrayList<>();
        for (WikiLink link : wikiLinks(contentMd)) {
            if (!out.contains(link.target())) {
                out.add(link.target());
            }
        }
        return out;
    }

    /** Inline {@code #tag} tokens (Obsidian style). */
    public static List<String> inlineTags(String contentMd) {
        List<String> out = new ArrayList<>();
        if (contentMd == null) {
            return out;
        }
        String stripped = INLINE_CODE.matcher(FENCED_CODE.matcher(contentMd).replaceAll(" ")).replaceAll(" ");
        Matcher m = TAG_TOKEN.matcher(stripped);
        while (m.find()) {
            String tag = m.group(1).trim();
            if (!tag.isEmpty() && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return out;
    }

    /** First ATX heading, used as the implicit title when the user leaves the title blank. */
    public static String firstHeading(String contentMd) {
        if (contentMd == null) {
            return null;
        }
        Matcher m = HEADING.matcher(FENCED_CODE.matcher(contentMd).replaceAll(" "));
        if (m.find()) {
            String h = m.group(1).trim();
            return h.isEmpty() ? null : h;
        }
        return null;
    }

    /** Plain-text projection (markdown syntax stripped) for card previews. */
    public static String plainPreview(String contentMd, int maxChars) {
        if (contentMd == null || contentMd.isEmpty()) {
            return "";
        }
        String text = FENCED_CODE.matcher(contentMd).replaceAll(" ");
        text = WIKI_LINK.matcher(text).replaceAll(mr ->
                mr.group(3) != null && !mr.group(3).isBlank() ? mr.group(3) : mr.group(1));
        text = IMAGE.matcher(text).replaceAll(" ");
        text = text.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s{0,3}[>\\-*+]\\s+", "")
                .replaceAll("(?m)^\\s{0,3}\\d+\\.\\s+", "")
                .replaceAll("(?m)^\\s*\\|.*\\|\\s*$", " ")
                .replaceAll("\\*\\*|__|~~|\\*|_", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (maxChars > 0 && text.length() > maxChars) {
            return text.substring(0, maxChars) + "…";
        }
        return text;
    }

    /** Normalized key for title/alias equality and link resolution. */
    /**
     * {@code [[标题]]} 的几种写法（含 {@code [[ 标题 ]]} 与 {@code [[标题|别名]} 前缀）：改名替换与
     * "谁引用了这篇"的扫描共用同一组形式，避免两处各写一套而漏掉某种写法。
     */
    public static List<String> wikiLinkVariants(String title) {
        return List.of("[[" + title + "]]", "[[ " + title + " ]]", "[[" + title + "|");
    }

    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Filesystem-safe slug. Latin/digit runs are transliterated as-is; everything else
     * (notably CJK) is kept because NTFS/APFS/ext4 all accept UTF-8 names, but path
     * separators and reserved characters are replaced.
     */
    public static String slug(String title) {
        if (title == null || title.isBlank()) {
            return "untitled";
        }
        String s = title.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "-")
                .replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^[.\\-]+|[.\\-]+$", "");
        if (s.isBlank()) {
            s = "untitled";
        }
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
