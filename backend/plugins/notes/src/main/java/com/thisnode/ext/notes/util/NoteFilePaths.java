package com.thisnode.ext.notes.util;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/** Static path helpers for the configured notes root. */
public final class NoteFilePaths {

    public static final String CARDS_DIR = "cardRefs";
    public static final String MD_SUFFIX = ".md";

    private static volatile Path root;

    private NoteFilePaths() {
    }

    /** Initialize once from the markdown-root resolved from the notes application.yml. */
    public static void configure(Path path) {
        root = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
    }

    public static Path root() {
        Path configured = root;
        if (configured == null) {
            throw new IllegalStateException("NoteFilePaths has not been configured from thisnode.markdown-root");
        }
        return configured;
    }

    public static Path noteMetaDir() {
        return root().resolve("meta");
    }

    public static Path ledgersDir() {
        return root().resolve("ledgers");
    }

    public static Path cardsDir() {
        return root().resolve(CARDS_DIR);
    }

    public static Path noteMetaFilePath(String noteId) {
        return noteMetaDir().resolve(safeSegment(noteId) + ".json");
    }

    public static Path tagsFilePath() {
        return ledgersDir().resolve("tags.json");
    }

    public static Path notebooksFilePath() {
        return ledgersDir().resolve("notebooks.json");
    }

    public static Path cardFilePath(String noteSlug, String cardSlug, String cardId) {
        return cardsDir().resolve(cardFilePrefix(noteSlug, cardSlug) + "-" + shortId(cardId) + MD_SUFFIX);
    }

    public static String cardFilePrefix(String noteSlug, String cardSlug) {
        String prefix = noteSlug == null || noteSlug.isBlank() ? "untitled" : noteSlug;
        if (cardSlug != null && !cardSlug.isBlank() && !cardSlug.equals(prefix)) {
            prefix += "-" + cardSlug;
        }
        return prefix;
    }

    public static boolean isContentFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(MD_SUFFIX);
    }

    public static String shortId(String cardId) {
        String hex = cardId == null ? "" : cardId.replace("-", "");
        if (hex.length() >= 8) {
            return hex.substring(0, 8).toLowerCase(Locale.ROOT);
        }
        return (hex + "00000000").substring(0, 8).toLowerCase(Locale.ROOT);
    }

    public static String cardSlug(String cardTitle, String contentMd) {
        if (cardTitle != null && !cardTitle.isBlank()) {
            return MarkdownUtils.slug(cardTitle);
        }
        String heading = MarkdownUtils.firstHeading(contentMd);
        if (heading != null && !heading.isBlank()) {
            return MarkdownUtils.slug(heading);
        }
        if (contentMd != null) {
            for (String line : contentMd.split("\\R")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return MarkdownUtils.slug(trimmed);
                }
            }
        }
        return "untitled";
    }

    public static String relative(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path root = root();
        return abs.startsWith(root)
                ? root.relativize(abs).toString().replace('\\', '/')
                : abs.toString().replace('\\', '/');
    }

    private static String safeSegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        return raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }
}
