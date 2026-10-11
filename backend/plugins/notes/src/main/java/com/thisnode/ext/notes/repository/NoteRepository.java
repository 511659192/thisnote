package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Repository
@Slf4j
public class NoteRepository {

    @Getter
    private final Path root;

    @Autowired
    public NoteRepository(NotesConfig config) {
        this(config.markdownRoot());
    }

    public NoteRepository(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public String insert(String title,
                         String type) {
        String id = IdUtil.randomUUID();
        Path path = Path.of(root.toString(), "meta", id + ".json");
        NoteMeta noteMeta = new NoteMeta().setId(id).setTitle(title).setType(type);
        JsonFileUtils.write(path, noteMeta);
        return id;
    }

    public void delete(String id) {
        NoteMeta note = getOne(id);
        if (note == null) {
            return;
        }
        for (String cardFileName : note.getCardFileNames()) {
            FileUtil.del(Path.of(root.toString(), "cards", cardFileName));
        }
        FileUtil.del((Path.of(root.toString(), "meta", id + ".json")));
    }

    @SneakyThrows
    public List<NoteMeta> getAll() {
        Path metaDir = Path.of(root.toString(), "meta");
        List<NoteMeta> notes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(metaDir, "*.json")) {
            for (Path filePath : stream) {
                NoteMeta meta = NoteMeta.parse(filePath);
                Optional.ofNullable(meta).ifPresent(notes::add);
            }
        }
        return notes;
    }

    /**
     * 只读一篇笔记的 meta（文件不在或读不出来给 {@code null}）—— 不会为了它扫全库。
     */
    public NoteMeta getOne(String id) {
        if (StrUtil.isBlank(id)) {
            return null;
        }

        Path filePath = Path.of(root.toString(), "meta", id + ".json");
        return NoteMeta.parse(filePath);
    }

    public String text(NoteMeta note) {
        return text(note == null ? null : note.getCardFileNames());
    }

    public String text(String id) {
        List<String> cardFileNames = cardFileNames(id);
        return text(cardFileNames);
    }

    public String text(List<String> cardFileNames) {
        if (cardFileNames == null || cardFileNames.isEmpty()) {
            return "";
        }
        StringBuilder joined = new StringBuilder();
        for (String cardFileName : cardFileNames) {
            Card data = Card.parse(Path.of(root.toString(), "cards", cardFileName));
            String part = data == null ? "" : data.getContent();
            if (part.isBlank()) {
                continue;
            }

            if (joined.length() > 0) {
                joined.append("\n\n");
            }
            joined.append(part.strip());
        }
        return joined.toString();
    }

    public Path writeCard(String title,
                          CardMeta cardMeta,
                          String content,
                          Path filePath) {
        JsonFileUtils.writeText(filePath, Card.compose(cardMeta, content));
        return filePath;
    }

    public record Owned(NoteMeta note, String cardId, Path file) {
    }


    private List<String> cardFileNames(String noteId) {
        NoteMeta note = getOne(noteId);
        return note == null || note.getCardFileNames() == null ? List.of() : note.getCardFileNames();
    }

    /**
     * 按 CardMeta.id 扫描各笔记登记的文件，确定归属。
     */
    public Owned ownerOf(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        for (NoteMeta note : getAll()) {
            for (String cardFileName : note.getCardFileNames()) {
                Path file = Path.of(root.toString(), "cards", cardFileName);
                Card data = Card.parse(file);
                if (data != null && data.getMeta() != null && cardId.equals(data.getMeta().getId())) {
                    return new Owned(note, cardId, file);
                }
            }
        }
        return null;
    }

    /**
     * 一张卡片的正文（找不到归属或文件不在时给空串）。
     */
    public String contentOf(String cardId) {
        Owned owned = ownerOf(cardId);
        if (owned == null) {
            return "";
        }
        Card data = Card.parse(owned.file());
        return data == null ? "" : data.getContent();
    }

    public CardMeta writeCard(String noteId,
                              CardMeta card,
                              String contentMd,
                              int slot) {
        NoteMeta note = getOne(noteId);
        if (note == null) {
            return null;
        }
        List<String> paths = new ArrayList<>(note.getCardFileNames());
        int at = indexOfCard(note, card.getId());
        Path knownFile = at >= 0 ? Path.of(root.toString(), paths.get(at)) : null;
        Path written = writeCard(note.getTitle(), card, contentMd, knownFile);
        String cardFileName = written.getFileName().toString();
        if (at >= 0) {
            paths.set(at, cardFileName);
        } else if (slot >= 0 && slot < paths.size()) {
            paths.add(slot, cardFileName);
        } else {
            paths.add(cardFileName);
        }
        note.setCardFileNames(paths);
        note.setUpdatedAt(System.currentTimeMillis());
        JsonFileUtils.write(Path.of(root.toString(), "meta", noteId + ".json"), note);
        return card;
    }

    private int indexOfCard(NoteMeta note,
                            String cardId) {
        for (int i = 0; i < note.getCardFileNames().size(); i++) {
            Card data = Card.parse(Path.of(root.toString(), "cards", note.getCardFileNames().get(i)));
            if (data != null && data.getMeta() != null && cardId.equals(data.getMeta().getId())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 只改元数据（重写头部注释，正文原样保留）。
     */
    @SneakyThrows
    public void writeCardMeta(CardMeta card,
                              Path contentFile) {
        String current = Files.readString(contentFile, StandardCharsets.UTF_8);
        if (current == null) {
            JsonFileUtils.writeText(contentFile, Card.compose(card, ""));
            return;
        }
        JsonFileUtils.writeText(contentFile, Card.compose(card, Card.content(current)));
    }

    public void replaceNoteContent(String noteId,
                                   String contentMd) {
        List<String> cards = cardFileNames(noteId);
        String body = contentMd == null ? "" : contentMd;
        if (cards.isEmpty()) {
            long now = System.currentTimeMillis();
            writeCard(noteId, CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null, now), body, -1);
            return;
        }
        for (int i = 0; i < cards.size(); i++) {
            Card data = Card.parse(Path.of(root.toString(), cards.get(i)));
            if (data == null || data.getMeta() == null) {
                continue;
            }
            writeCard(noteId, data.getMeta(), i == 0 ? body : "", i);
        }
    }


    public int cardCount(String noteId) {
        return cardFileNames(noteId).size();
    }

    public CardMeta card(String cardId) {
        Owned owned = ownerOf(cardId);
        if (owned == null) {
            return null;
        }
        Card data = Card.parse(owned.file());
        return data == null ? null : data.getMeta();
    }

    public List<CardMeta> cards(String noteId) {
        List<CardMeta> out = new ArrayList<>();
        for (String cardFileName : cardFileNames(noteId)) {
            Card data = Card.parse(Path.of(root.toString(), "cards", cardFileName));
            if (data != null && data.getMeta() != null) {
                out.add(data.getMeta());
            }
        }
        return out;
    }


    public String noteIdOf(String cardId) {
        Owned owned = ownerOf(cardId);
        return owned == null ? null : owned.note().getId();
    }

    public String content(String cardId) {
        return contentOf(cardId);
    }

    public String contentOnDisk(String cardId) {
        return contentOf(cardId);
    }

}
