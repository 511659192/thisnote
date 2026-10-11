package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes card files. Note ownership and manifests are coordinated by services.
 */
@Repository
@RequiredArgsConstructor
public class CardRepository {

    public static final int DEFAULT_W = 720;
    public static final int DEFAULT_H = 260;
    public static final int MIN_W = 240;
    public static final int MIN_H = 88;
    public static final int FLOW_GAP = 16;
    public static final int FLOW_X = 0;

    private final NotesConfig notesConfig;

    public Card get(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        Path file = notesConfig.getCardsDir().resolve(fileName);
        return Files.isRegularFile(file) ? Card.parse(file) : null;
    }

    public List<Card> getAll(List<String> fileNames) {
        if (fileNames == null || fileNames.isEmpty()) {
            return List.of();
        }
        List<Card> cards = new ArrayList<>(fileNames.size());
        for (String fileName : fileNames) {
            Card card = get(fileName);
            if (card != null && card.getMeta() != null) {
                cards.add(card);
            }
        }
        return cards;
    }

    public List<String> getContents(List<String> fileNames) {
        return getAll(fileNames).stream().map(Card::getContent).toList();
    }

    public String getJoinedContents(List<String> fileNames) {
        StringBuilder joined = new StringBuilder();
        for (String part : getContents(fileNames)) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append("\n\n");
            }
            joined.append(part.strip());
        }
        return joined.toString();
    }

    /**
     * Write to a known file or choose a unique descriptive filename.
     */
    public String save(String noteTitle,
                       CardMeta meta,
                       String contentMd,
                       String knownFileName) {
        Path file = knownFileName == null
                ? resolveContentPath(noteTitle, cardSlug(meta.getTitle()), meta.getId())
                : notesConfig.getCardsDir().resolve(knownFileName);
        JsonFileUtils.writeText(file, Card.compose(meta, contentMd));
        return file.getFileName().toString();
    }

    /**
     * Rewrite only a card header while preserving its body.
     */
    public void save(String fileName,
                     CardMeta meta) {
        Card card = get(fileName);
        String body = card == null ? "" : card.getContent();
        JsonFileUtils.writeText(notesConfig.getCardsDir().resolve(fileName), Card.compose(meta, body));
    }

    public void delete(List<String> fileNames) {
        if (fileNames == null) {
            return;
        }
        for (String fileName : fileNames) {
            FileUtil.del(notesConfig.getCardsDir().resolve(fileName));
        }
    }

    private Path resolveContentPath(String noteTitle,
                                    String cardTitle,
                                    String cardId) {
        String noteSlug = cardSlug(noteTitle);
        String prefix = cardFilePrefix(noteSlug, cardTitle);
        Path candidate = notesConfig.getCardsDir().resolve(prefix + "-" + shortId(cardId) + ".md");
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String hex = cardId.replace("-", "");
        for (int length = 12; length < hex.length(); length += 4) {
            Path longer = notesConfig.getCardsDir().resolve(prefix + "-" + hex.substring(0, length) + ".md");
            if (!Files.exists(longer)) {
                return longer;
            }
        }
        return notesConfig.getCardsDir().resolve(prefix + "-" + cardId + ".md");
    }

    private static String cardSlug(String title) {
        if (title == null || title.isBlank()) {
            return Long.toString(System.currentTimeMillis());
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

    private static String cardFilePrefix(String noteSlug,
                                         String cardSlug) {
        String prefix = noteSlug == null || noteSlug.isBlank() ? "untitled" : noteSlug;
        if (cardSlug != null && !cardSlug.isBlank() && !cardSlug.equals(prefix)) {
            prefix += "-" + cardSlug;
        }
        return prefix;
    }

    private static String shortId(String cardId) {
        String hex = cardId == null ? "" : cardId.replace("-", "");
        if (hex.length() >= 8) {
            return hex.substring(0, 8).toLowerCase(Locale.ROOT);
        }
        return (hex + "00000000").substring(0, 8).toLowerCase(Locale.ROOT);
    }
}
