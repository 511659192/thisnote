package com.thisnode.ext.notes.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown helpers: {@code [[wiki link]]} extraction (F-L-01), frontmatter parsing for
 * Obsidian import, and title/filesystem slug rules.
 */
public final class MarkdownUtils {

    /** {@code [[Target]]}, {@code [[Target|Label]]}, {@code [[Target#Heading]]}. */
    private static final Pattern WIKI_LINK = Pattern.compile(
            "\\[\\[\\s*([^\\[\\]|#]+?)\\s*(?:#([^\\[\\]|]*))?\\s*(?:\\|([^\\[\\]]*))?\\s*\\]\\]");

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");
    private static final Pattern IMAGE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?", Pattern.DOTALL);
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

    public static List<String> images(String contentMd) {
        List<String> out = new ArrayList<>();
        if (contentMd == null) {
            return out;
        }
        Matcher m = IMAGE.matcher(contentMd);
        while (m.find()) {
            out.add(m.group(2));
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

    /** Parse a leading YAML-style frontmatter block into a flat string map. */
    public static Map<String, String> frontmatter(String contentMd) {
        Map<String, String> map = new LinkedHashMap<>();
        if (contentMd == null) {
            return map;
        }
        Matcher m = FRONTMATTER.matcher(contentMd);
        if (!m.find()) {
            return map;
        }
        for (String line : m.group(1).split("\\r?\\n")) {
            int idx = line.indexOf(':');
            if (idx <= 0) {
                continue;
            }
            String key = line.substring(0, idx).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(idx + 1).trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1);
            }
            value = value.replaceAll("^[\"']|[\"']$", "");
            if (!key.isEmpty()) {
                map.put(key, value);
            }
        }
        return map;
    }

    /**
     * Remove a leading frontmatter block (used when importing, since DB columns own it).
     *
     * <p>实现只在核心这一份：契约层曾经也有一份（{@code MarkdownText}），但一直没有第二个
     * 使用者，就搬回来了 —— 两边各自演化出微妙差异的风险，比"少写一遍"更贵。
     */
    public static String stripFrontmatter(String contentMd) {
        if (contentMd == null) {
            return "";
        }
        String text = contentMd;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1); // 容忍 BOM
        }
        if (!text.startsWith("---")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd < 0 || !text.substring(0, firstLineEnd).trim().equals("---")) {
            return text;
        }
        int closing = text.indexOf("\n---", firstLineEnd);
        if (closing < 0) {
            return text;
        }
        int afterClosing = text.indexOf('\n', closing + 1);
        if (afterClosing < 0) {
            return "";
        }
        return text.substring(afterClosing + 1);
    }

    /**
     * 把一篇笔记渲染成"带 frontmatter 的完整 markdown"。
     *
     * <p>这是**导出/备份**形态（插件把笔记同步到网盘、导出单篇笔记时用），不是卡片在磁盘上的
     * 形态：卡片文件只有正文，标签与别名存在笔记 meta 里（见 store 包）。所以这里必须由调用方
     * 显式传入 tags/aliases，而不是去读数据库。
     */
    public static String renderNote(String contentMd, String type, List<String> tags,
                                    List<String> aliases) {
        StringBuilder sb = new StringBuilder();
        String content = stripFrontmatter(contentMd == null ? "" : contentMd);
        boolean typed = type != null && !"note".equals(type) && !"daily".equals(type);
        boolean hasTags = tags != null && !tags.isEmpty();
        boolean hasAliases = aliases != null && !aliases.isEmpty();
        if (typed || hasTags || hasAliases) {
            sb.append("---\n");
            if (typed) {
                sb.append("type: ").append(type).append('\n');
            }
            if (hasTags) {
                sb.append("tags: [")
                        .append(String.join(", ", tags.stream().map(t -> "\"" + t + "\"").toList()))
                        .append("]\n");
            }
            if (hasAliases) {
                sb.append("aliases: [")
                        .append(String.join(", ", aliases.stream().map(t -> "\"" + t + "\"").toList()))
                        .append("]\n");
            }
            sb.append("---\n\n");
        }
        sb.append(content);
        return sb.toString();
    }

    /** Split a frontmatter list value ({@code a, b} or {@code [a, b]}) into items. */
    public static List<String> splitList(String value) {
        List<String> out = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return out;
        }
        for (String part : value.split(",")) {
            String v = part.trim().replaceAll("^[\"']|[\"']$", "");
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
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
