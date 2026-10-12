package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    /**
     * Create a card with a stable ID and a human-readable, title-derived filename.
     */
    @SneakyThrows
    public String saveNew(CardMeta meta,
                          String content,
                          String title) {
        String cardId = meta.getId() == null || meta.getId().isBlank() ? UUID.randomUUID().toString() : meta.getId();
        meta.setId(cardId).setUpdatedAt(System.currentTimeMillis());

        Path dir = notesConfig.getCardsDir();
        String prefix = title == null || title.isBlank() ? "draft-" + cardId : title;
        String cardContent = Card.compose(meta, content);
        for (int number = 1; ; number++) {
            String suffix = "_" + number;
            String fileName = prefix + suffix + ".md";
            Path file = dir.resolve(fileName);
            if (Files.exists(file)) continue;
            Files.writeString(file, cardContent);
            fileIndex.addCard(cardId, fileName);
            return cardId;
        }
    }

    /**
     * Rewrite the card content using its stable ID to resolve the physical path.
     */
    public String save(CardMeta meta,
                       String contentMd,
                       String cardId) {
        meta.setUpdatedAt(System.currentTimeMillis());
        String fileName = fileIndex.cardFileName(cardId);
        if (fileName == null) throw new IllegalStateException("卡片 ID 不在文件索引中：" + cardId);
        JsonFileUtils.writeText(notesConfig.getCardsDir().resolve(fileName), Card.compose(meta, contentMd));
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
            FileUtil.del(notesConfig.getCardsDir().resolve(fileName));
        }
    }
}
