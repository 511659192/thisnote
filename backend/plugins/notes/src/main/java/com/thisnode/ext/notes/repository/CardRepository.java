package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.Card;
import com.thisnode.ext.notes.model.Card.CardMeta;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CardRepository {

    public static final int MIN_W = 240;
    public static final int MIN_H = 88;

    private final NotesConfig notesConfig;
    private final IdFileIndex fileIndex;
    private final FileHelper fileHelper;

    public Card getOne(String cardId) {
        if (cardId == null || cardId.isBlank()) return null;
        String fileName = fileIndex.cardFileName(cardId);
        if (fileName == null) return null;
        Path file = notesConfig.getCardsDir().resolve(fileName);
        return Files.isRegularFile(file) ? Card.parse(file) : null;
    }

    public List<Card> getAll(List<String> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) return List.of();
        List<Card> cards = new ArrayList<>(cardIds.size());
        for (String cardId : cardIds) {
            Card card = getOne(cardId);
            if (card != null && card.getMeta() != null) cards.add(card);
        }
        return cards;
    }

    public List<Card> getAllIndexed() {
        return getAll(new ArrayList<>(fileIndex.cardFiles().keySet()));
    }

    /**
     * Create a card with a stable ID and a human-readable, title-derived filename.
     */
    @SneakyThrows
    public String saveNew(CardMeta meta,
                          String content,
                          String title) {
        String cardId = meta.getCardId() == null || meta.getCardId().isBlank() ? UUID.randomUUID().toString() : meta.getCardId();
        meta.setCardId(cardId);

        Path dir = notesConfig.getCardsDir();
        Files.createDirectories(dir);
        String prefix = title == null || title.isBlank() ? "tmp-" + cardId : title;
        String cardContent = Card.compose(meta, content);
        for (int number = 1; ; number++) {
            String suffix = "_" + number;
            String fileName = prefix + suffix + ".md";
            Path file = dir.resolve(fileName);
            if (Files.exists(file)) continue;
            fileHelper.fileWritten(file, cardContent);
            fileIndex.addCard(cardId, fileName);
            return cardId;
        }
    }

    @SneakyThrows
    public void renameFiles(List<String> cardIds,
                            String noteTitle) {
        if (cardIds == null || cardIds.isEmpty()) return;

        Path dir = notesConfig.getCardsDir();
        boolean draftTitle = noteTitle == null || noteTitle.isBlank();
        for (String cardId : cardIds) {
            String oldFileName = fileIndex.cardFileName(cardId);
            if (oldFileName == null) continue;

            String prefix = draftTitle ? "draft-" + cardId : noteTitle;
            for (int number = 1; ; number++) {
                String newFileName = prefix + "_" + number + ".md";
                if (newFileName.equals(oldFileName)) break;
                Path newFile = dir.resolve(newFileName);
                if (Files.exists(newFile)) continue;

                Path oldFile = dir.resolve(oldFileName);
                fileHelper.fileMoved(oldFile, newFile);
                fileIndex.updateCardFileName(cardId, newFileName);
                break;
            }
        }
    }

    /**
     * Rewrite the card content using its stable ID to resolve the physical path.
     */
    public String save(CardMeta meta,
                       String contentMd,
                       String cardId) {
        String fileName = fileIndex.cardFileName(cardId);
        if (fileName == null) throw new IllegalStateException("卡片 ID 不在文件索引中：" + cardId);
        Path file = notesConfig.getCardsDir().resolve(fileName);
        fileHelper.fileWritten(file, Card.compose(meta, contentMd));
        return cardId;
    }

    public void save(CardMeta meta,
                     String cardId) {
        Card card = getOne(cardId);
        String body = card == null ? "" : card.getContent();
        this.save(meta, body, cardId);
    }

    public void delete(List<String> cardIds) {
        if (cardIds == null) return;
        for (String cardId : cardIds) {
            String fileName = fileIndex.cardFileName(cardId);
            if (fileName == null) continue;
            fileIndex.removeCard(cardId);
            Path file = notesConfig.getCardsDir().resolve(fileName);
            fileHelper.fileDeleted(file);
        }
    }
}
