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

/**
 * Reads and writes card files. Note ownership and manifests are coordinated by services.
 */
@Repository
@RequiredArgsConstructor
public class CardRepository {

    public static final int MIN_W = 240;
    public static final int MIN_H = 88;

    private final NotesConfig notesConfig;

    public Card getOne(String fileName) {
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
            Card card = getOne(fileName);
            if (card != null && card.getMeta() != null) {
                cards.add(card);
            }
        }
        return cards;
    }

    /**
     * Write using the card ID as the filename, or preserve a known filename.
     */
    public String save(CardMeta meta,
                       String contentMd,
                       String knownFileName) {
        String fileName = knownFileName == null ? meta.getId() + ".md" : knownFileName;
        Path file = notesConfig.getCardsDir().resolve(fileName);
        JsonFileUtils.writeText(file, Card.compose(meta, contentMd));
        return file.getFileName().toString();
    }

    /**
     * Rewrite only a card header while preserving its body.
     */
    public void save(String fileName,
                     CardMeta meta) {
        Card card = getOne(fileName);
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
}
