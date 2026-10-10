package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.util.Markdown;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 笔记目录的命名规则与路径解析。
 *
 * <p>布局（{@code <root>} = {@code thisnode.markdown-root}），按域分目录：
 * <pre>
 *   notes\cards\&lt;笔记名&gt;-&lt;卡片描述&gt;-&lt;随机串&gt;.md   一张卡片一个 md，纯正文（不带 frontmatter）
 *   notes\meta\&lt;noteId&gt;.json                       笔记级元数据（标题 / 标签 / 归属分组）
 *   notes\ledgers\notebooks.json                   分组台账
 *   notes\ledgers\tags.json                        标签台账
 *   notes\ledgers\settings.json                    笔记侧设置
 *   notes\logs\ops.jsonl                           操作日志，一行一条
 * </pre>
 *
 * <p>cards/ 之外都是应用目录；cards 目录里**只有**卡片 md，扫描不需要黑名单。
 *
 * <p>文件名分两段：人读的 slug 与机器认的 cardId 前 8 位。slug 让文件在资源管理器里像笔记，
 * 短 id 让两张同名卡片不互相覆盖。**身份由 meta 里的完整 id 决定**，改名不改变身份。
 */
public final class NoteFilePaths {

    /** 卡片内容目录。 */
    public static final String CARDS_DIR = "cards";
    public static final String MD_SUFFIX = ".md";
    public static final String META_SUFFIX = ".meta.json";

    private final Path root;

    public NoteFilePaths(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** 笔记 meta 目录：里面只有 {@code <noteId>.json}。 */
    public Path noteMetaDir() {
        return root.resolve("meta");
    }

    /** 单文件台账目录：分组 / 标签 / 设置。 */
    public Path ledgersDir() {
        return root.resolve("ledgers");
    }

    /** 日志目录。 */
    public Path logsDir() {
        return root.resolve("logs");
    }

    /** 卡片内容目录。 */
    public Path cardsDir() {
        return root.resolve(CARDS_DIR);
    }

    /** 一篇笔记的元数据文件；笔记本身没有 md，所以这里只放 json。 */
    public Path noteMeta(String noteId) {
        return noteMetaDir().resolve(safeSegment(noteId) + ".json");
    }

    public Path settingsFile() {
        return ledgersDir().resolve("settings.json");
    }

    /** 标签台账：一个文件装全部标签（名称 / 颜色 / 建立与更新时间），见 {@link TagBook}。 */
    public Path tagsFile() {
        return ledgersDir().resolve("tags.json");
    }

    /** 分组（笔记本）台账（读与写同一个文件）。 */
    public Path notebooksFile() {
        return ledgersDir().resolve("notebooks.json");
    }

    public Path opsFile() {
        return logsDir().resolve("ops.jsonl");
    }

    /**
     * 一张卡片的内容文件：{@code <笔记名>-<卡片描述>-<随机串>.md}，收在 cards/ 目录下。
     *
     * <p>三段各有用处：笔记名让人一眼看出这张卡属于哪篇，卡片描述说明它是什么，随机串保证同名
     * 同描述的两张卡不会互相覆盖。**身份在 meta 的 id 里，文件名只是给人看的** —— 所以标题变了
     * 不会去改文件名（那会让 Obsidian / Git 看到一次无意义的增删）。
     */
    public Path cardFilePath(String noteSlug, String cardSlug, String cardId) {
        return cardsDir().resolve(cardFilePrefix(noteSlug, cardSlug) + "-" + shortId(cardId) + MD_SUFFIX);
    }

    /** 卡片文件名前缀：{@code <笔记名>[-<卡片描述>]}。 */
    public static String cardFilePrefix(String noteSlug, String cardSlug) {
        String prefix = noteSlug == null || noteSlug.isBlank() ? "untitled" : noteSlug;
        if (cardSlug != null && !cardSlug.isBlank() && !cardSlug.equals(prefix)) {
            prefix = prefix + "-" + cardSlug;
        }
        return prefix;
    }

    /** 卡片的 sidecar：{@code x.md} → {@code x.meta.json}（同目录同基名）。 */
    public static Path sidecarFor(Path contentFile) {
        String name = contentFile.getFileName().toString();
        String base = name.endsWith(MD_SUFFIX)
                ? name.substring(0, name.length() - MD_SUFFIX.length())
                : name;
        return contentFile.resolveSibling(base + META_SUFFIX);
    }

    public static boolean isSidecar(Path file) {
        return file.getFileName().toString().endsWith(META_SUFFIX);
    }

    /** 会被扫描聚合的卡片内容文件：{@code .md}，且不是 sidecar。 */
    public boolean isContentFile(Path file) {
        String name = file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(MD_SUFFIX)
                && !name.toLowerCase(Locale.ROOT).endsWith(META_SUFFIX);
    }

    /** 卡片在 meta 里的 id 是完整 UUID，文件名只取前 8 位十六进制。 */
    public static String shortId(String cardId) {
        String hex = cardId == null ? "" : cardId.replace("-", "");
        if (hex.length() >= 8) {
            return hex.substring(0, 8).toLowerCase(Locale.ROOT);
        }
        return (hex + "00000000").substring(0, 8).toLowerCase(Locale.ROOT);
    }

    /**
     * 卡片的可读名：优先用户给卡片起的标题，其次正文第一个标题或第一行，都没有才叫 untitled。
     * 卡片标题是可选的，所以这里必须能退到正文。
     */
    public static String cardSlug(String cardTitle, String contentMd) {
        if (cardTitle != null && !cardTitle.isBlank()) {
            return Markdown.slug(cardTitle);
        }
        String heading = Markdown.firstHeading(contentMd);
        if (heading != null && !heading.isBlank()) {
            return Markdown.slug(heading);
        }
        if (contentMd != null) {
            for (String line : contentMd.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return Markdown.slug(trimmed);
                }
            }
        }
        return "untitled";
    }

    /** 相对根目录的正斜杠路径，用于日志与 API 展示。 */
    public String relative(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        return abs.startsWith(root)
                ? root.relativize(abs).toString().replace('\\', '/')
                : abs.toString().replace('\\', '/');
    }

    /** 把任意外部字符串收成一个安全的文件名片段（id 来自 meta，宁可换成下划线也不要穿目录）。 */
    private static String safeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }
}
