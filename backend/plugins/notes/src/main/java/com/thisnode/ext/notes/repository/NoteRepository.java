package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.*;
import com.thisnode.ext.notes.util.JsonFileUtils;
import com.thisnode.ext.notes.util.MarkdownUtils;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

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
        String noteMetaId = IdUtil.randomUUID();
        Path path = Path.of(root.toString(), "meta", noteMetaId + ".json");
        NoteMeta noteMeta = NoteMeta.newNoteMeta(noteMetaId, title, type, null);
        JsonFileUtils.write(path, noteMeta);
        return noteMetaId;
    }

    public void delete(String noteMetaId) {
        NoteMeta note = getOneNoteMeta(noteMetaId);
        if (note == null) {
            return;
        }
        for (String cardFileName : note.getCardPaths()) {
            FileUtil.del(Path.of(root.toString(), "cards", cardFileName));
        }
        FileUtil.del((Path.of(root.toString(), "meta", noteMetaId + ".json")));
    }

    @SneakyThrows
    public CardData readCard(Path file) {
        String raw = Files.readString(file, StandardCharsets.UTF_8);;
        if (raw == null) {
            return null;
        }
        return new CardData(CardFile.metaOf(raw), CardFile.bodyOf(raw));
    }

    private List<String> listCardFiles() {
        List<String> paths = new ArrayList<>();
        Path cardsDir = Path.of(root.toString(), "cards");
        if (!Files.isDirectory(cardsDir)) {
            return Collections.emptyList();
        }
        try (Stream<Path> stream = Files.walk(cardsDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (isContentFile(file)) {
                    paths.add(relative(file));
                }
            }
        } catch (IOException e) {
            log.warn("卡片目录无法遍历：{}（{}）", relative(cardsDir), e.toString());
        }
        return paths;
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
                    log.warn("元数据无法解析，已跳过：{}（{}）", relative(file), e.toString());
                    continue;
                }
                if (meta == null) {
                    continue;
                }

                notes.add(meta);
            }
        } catch (IOException | DirectoryIteratorException e) {
            log.warn("笔记元数据目录无法读取：{}（{}）", relative(metaDir), e.toString());
        }
        return notes;
    }

    /**
     * 只读一篇笔记的 meta（文件不在或读不出来给 {@code null}）—— 不会为了它扫全库。
     */
    public NoteMeta getOneNoteMeta(String noteMetaId) {
        if (noteMetaId == null || noteMetaId.isBlank()) {
            return null;
        }
        Path metaDir = Path.of(root.toString(), "meta").normalize();
        Path file;
        try {
            file = Path.of(metaDir.toString(), noteMetaId + ".json").normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonFileUtils.read(file, NoteMeta.class);
        } catch (IOException | RuntimeException e) {
            log.warn("元数据无法解析，已跳过：{}（{}）", relative(file), e.toString());
            return null;
        }
    }


    public String noteMarkdown(String noteId) {
        StringBuilder joined = new StringBuilder();
        for (String cardFileName : cardPathsOf(noteId)) {
            CardData data = readCard(Path.of(root.toString(), "cards", cardFileName));
            String part = data == null ? "" : data.content();
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
        Path file = knownFile != null ? knownFile : resolveContentPath(noteTitle, cardSlug(card.title(), contentMd), card.id());
        JsonFileUtils.writeText(file, CardFile.compose(card, contentMd));
        return file;
    }

    public void ensureDirs() {
        try {
            for (Path dir : new Path[]{Path.of(root.toString(), "cards"), Path.of(root.toString(), "meta"), Path.of(root.toString(), "ledgers")}) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建笔记目录结构 " + root, e);
        }
    }

    private boolean isContentFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private String relative(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        return absolute.startsWith(root)
                ? root.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString().replace('\\', '/');
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

    /** 一张卡片的归属；卡片 ID 仅由卡片文件头部的 {@link CardMeta#id()} 定义。 */
    public record Owned(NoteMeta note, String cardId, Path file) {
    }

    /** 一篇笔记的卡片相对路径清单（顺序即阅读顺序）；笔记不存在给空清单。 */
    private List<String> cardPathsOf(String noteId) {
        NoteMeta note = getOneNoteMeta(noteId);
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
                CardData data = readCard(file);
                if (data != null && data.meta() != null && cardId.equals(data.meta().id())) {
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
        CardData data = readCard(owned.file());
        return data == null ? "" : data.content();
    }

    public CardMeta writeCard(String noteId, CardMeta card, String contentMd, int slot) {
        NoteMeta note = getOneNoteMeta(noteId);
        if (note == null) {
            return null;
        }
        List<String> paths = new ArrayList<>(note.getCardPaths());
        int at = indexOfCard(note, card.id());
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
        note.touch(System.currentTimeMillis());
        JsonFileUtils.write(Path.of(root.toString(), "meta", noteId + ".json"), note);
        return card;
    }

    private int indexOfCard(NoteMeta note, String cardId) {
        for (int i = 0; i < note.getCardPaths().size(); i++) {
            CardData data = readCard(Path.of(root.toString(), "cards", note.getCardPaths().get(i)));
            if (data != null && data.meta() != null && cardId.equals(data.meta().id())) {
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
            JsonFileUtils.writeText(contentFile, CardFile.compose(card, ""));
            return;
        }
        JsonFileUtils.writeText(contentFile, CardFile.compose(card, CardFile.bodyOf(current)));
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
            CardData data = readCard(Path.of(root.toString(), cards.get(i)));
            if (data == null || data.meta() == null) {
                continue;
            }
            writeCard(noteId, data.meta(), i == 0 ? body : "", i);
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
        CardData data = readCard(owned.file());
        return data == null ? null : data.meta();
    }

    public List<CardMeta> cards(String noteId) {
        List<CardMeta> out = new ArrayList<>();
        for (String cardFileName : cardPathsOf(noteId)) {
            CardData data = readCard(Path.of(root.toString(), "cards", cardFileName));
            if (data != null && data.meta() != null) {
                out.add(data.meta());
            }
        }
        return out;
    }

    public Map<String, Object> extension(String cardId, String pluginId) {
        CardMeta card = card(cardId);
        if (card == null) {
            return Map.of();
        }
        Object value = card.ext().get(pluginId);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
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

    public int dropMissingCards() {
        int dropped = 0;
        for (NoteMeta note : findAll()) {
            List<String> kept = new ArrayList<>();
            boolean changed = false;
            for (String cardFileName : note.getCardPaths()) {
                if (Files.isRegularFile(Path.of(root.toString(), "cards", cardFileName))) {
                    kept.add(cardFileName);
                } else {
                    changed = true;
                    dropped++;
                }
            }
            if (changed) {
                note.setCardPaths(kept);
                note.touch(System.currentTimeMillis());
                JsonFileUtils.write(Path.of(root.toString(), "meta", note.getId() + ".json"), note);
            }
        }
        return dropped;
    }

    public int claimUnmanaged() {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (NoteMeta note : findAll()) {
            known.addAll(note.getCardPaths());
        }
        int claimed = 0;
        for (String relative : listCardFiles()) {
            String cardFileName = Path.of(relative).getFileName().toString();
            if (known.contains(cardFileName)) {
                continue;
            }
            claim(relative);
            claimed++;
        }
        return claimed;
    }

    private void claim(String relative) {
        Path file = Path.of(root.toString(), relative);
        CardData data = noteStoreRead(file);
        long now = System.currentTimeMillis();
        String content = data == null ? "" : data.content();
        CardMeta card = data != null && data.meta() != null ? data.meta() : CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null, now);
        String title = titleFromContent(content, file);
        // 认领的笔记也必须归属一个分组：直接落默认分组（不变量在创建点收口）。
        String noteId = IdUtil.randomUUID();
        NoteMeta note = NoteMeta.newNoteMeta(noteId, title, "note", NotebookBook.DEFAULT_GROUP_ID);
        note.setCardPaths(List.of(file.getFileName().toString()));
        JsonFileUtils.write(Path.of(root.toString(), "meta", noteId + ".json"), note);
        writeCardMeta(card, file);
    }

    private CardData noteStoreRead(Path file) {
        return readCard(file);
    }

    private static String titleFromContent(String content, Path file) {
        String heading = MarkdownUtils.firstHeading(content);
        if (heading != null && !heading.isBlank()) {
            return heading;
        }
        String name = file.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".md")) {
            name = name.substring(0, name.length() - ".md".length());
        }
        name = name.replaceFirst("-[0-9a-fA-F]{8,}$", "");
        return name.isBlank() ? "无标题笔记" : name;
    }
}
