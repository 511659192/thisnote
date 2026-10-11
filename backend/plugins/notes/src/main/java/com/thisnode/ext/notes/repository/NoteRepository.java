package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.*;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import com.thisnode.ext.notes.util.MarkdownUtils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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

    public String insert(String title, String type) {
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
        for (String cardFileName : note.getCardPaths()) {
            FileUtil.del(Path.of(root.toString(), "cards", cardFileName));
        }
        FileUtil.del((Path.of(root.toString(), "meta", id + ".json")));
    }

    public List<NoteMeta> findAll() {
        Path metaDir = Path.of(root.toString(), "meta");
        if (!Files.isDirectory(metaDir)) {
            return Collections.emptyList();
        }

        List<NoteMeta> notes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(metaDir, "*.json")) {
            for (Path file : stream) {
                NoteMeta meta;
                try {
                    meta = JsonFileUtils.read(file, NoteMeta.class);
                } catch (IOException | RuntimeException e) {
                    String displayPath = root.relativize(file).toString().replace('\\', '/');
                    log.warn("元数据无法解析，已跳过：{}（{}）", displayPath, e.toString());
                    continue;
                }

                if (meta == null) {
                    continue;
                }

                notes.add(meta);
            }
        } catch (IOException | DirectoryIteratorException e) {
            String displayPath = root.relativize(metaDir).toString().replace('\\', '/');
            log.error("笔记元数据目录无法读取：{}（{}）", displayPath, e.toString());
        }
        return notes;
    }

    /**
     * 只读一篇笔记的 meta（文件不在或读不出来给 {@code null}）—— 不会为了它扫全库。
     */
    public NoteMeta getOne(String noteMetaId) {
        if (noteMetaId == null || noteMetaId.isBlank()) {
            return null;
        }
        Path metaDir = Path.of(root.toString(), "meta").normalize();
        Path filePath;
        try {
            filePath = Path.of(metaDir.toString(), noteMetaId + ".json").normalize();
        } catch (InvalidPathException e) {
            return null;
        }

        return NoteMeta.parse(filePath);
    }


    public String text(NoteMeta note) {
        List<String> cardPaths = Optional.of(note.getCardPaths()).orElse(Collections.emptyList());
        return text(cardPaths);
    }

    public String text(String noteId) {
        List<String> cardPaths = cardPathsOf(noteId);
        return text(cardPaths);
    }

    public String text(List<String> cardPaths) {
        StringBuilder joined = new StringBuilder();
        for (String cardFileName : cardPaths) {
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

    public Path writeCard(String noteTitle, CardMeta card, String contentMd, Path knownFile) {
        Path file = knownFile != null ? knownFile : resolveContentPath(noteTitle, cardSlug(card.getTitle(), contentMd), card.getId());
        JsonFileUtils.writeText(file, Card.compose(card, contentMd));
        return file;
    }

    private static String cardSlug(String cardTitle, String contentMd) {
        if (cardTitle != null && !cardTitle.isBlank()) {
            return MarkdownUtils.slug(cardTitle);
        }
        String heading = MarkdownUtils.firstHeading(contentMd);
        if (heading != null && !heading.isBlank()) {
            return MarkdownUtils.slug(heading);
        }
        if (contentMd != null) {
            for (String line : contentMd.split("\\R")) {
                if (!line.isBlank()) {
                    return MarkdownUtils.slug(line.trim());
                }
            }
        }
        return "untitled";
    }

    private static String cardFilePrefix(String noteSlug, String cardSlug) {
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

    private Path resolveContentPath(String noteTitle, String cardSlug, String cardId) {
        String noteSlug = cardSlug(noteTitle, null);
        String prefix = cardFilePrefix(noteSlug, cardSlug);
        Path candidate = Path.of(root.toString(), "cards", prefix + "-" + shortId(cardId) + ".md");
        if (!Files.exists(candidate)) {
            return candidate;
        }
        // 笔记名、卡片描述都一样（同名同描述的两张卡）：把随机串逐段加长，直到不撞车。
        String hex = cardId.replace("-", "");
        for (int len = 12; len < hex.length(); len += 4) {
            Path longer = Path.of(root.toString(), "cards", prefix + "-" + hex.substring(0, len) + ".md");
            if (!Files.exists(longer)) {
                return longer;
            }
        }
        return Path.of(root.toString(), "cards", prefix + "-" + cardId + ".md");
    }

    // ================================================================ 卡片与视图编排（原 NoteCompositionService，纯原语部分）

    /** 一张卡片的归属；卡片 ID 仅由卡片文件头部的 {@link CardMeta#getId()} 定义。 */
    public record Owned(NoteMeta note, String cardId, Path file) {
    }

    /** 一篇笔记的卡片相对路径清单（顺序即阅读顺序）；笔记不存在给空清单。 */
    private List<String> cardPathsOf(String noteId) {
        NoteMeta note = getOne(noteId);
        return note == null ? List.of() : note.getCardPaths();
    }

    /** 按 CardMeta.id 扫描各笔记登记的文件，确定归属。 */
    public Owned ownerOf(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        for (NoteMeta note : findAll()) {
            for (String cardFileName : note.getCardPaths()) {
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

    public CardMeta writeCard(String noteId, CardMeta card, String contentMd, int slot) {
        NoteMeta note = getOne(noteId);
        if (note == null) {
            return null;
        }
        List<String> paths = new ArrayList<>(note.getCardPaths());
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
        note.setCardPaths(paths);
        note.setUpdatedAt(System.currentTimeMillis());
        JsonFileUtils.write(Path.of(root.toString(), "meta", noteId + ".json"), note);
        return card;
    }

    private int indexOfCard(NoteMeta note, String cardId) {
        for (int i = 0; i < note.getCardPaths().size(); i++) {
            Card data = Card.parse(Path.of(root.toString(), "cards", note.getCardPaths().get(i)));
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
    public void writeCardMeta(CardMeta card, Path contentFile) {
        String current = Files.readString(contentFile, StandardCharsets.UTF_8);
        if (current == null) {
            JsonFileUtils.writeText(contentFile, Card.compose(card, ""));
            return;
        }
        JsonFileUtils.writeText(contentFile, Card.compose(card, Card.bodyOf(current)));
    }

    public void replaceNoteContent(String noteId, String contentMd) {
        List<String> cards = cardPathsOf(noteId);
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
        return cardPathsOf(noteId).size();
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
        for (String cardFileName : cardPathsOf(noteId)) {
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
