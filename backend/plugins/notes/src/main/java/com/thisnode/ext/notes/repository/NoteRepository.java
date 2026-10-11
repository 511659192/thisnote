package com.thisnode.ext.notes.repository;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONFactory;
import com.thisnode.ext.notes.domain.*;
import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.util.JsonFileUtils;
import com.thisnode.ext.notes.util.MarkdownUtils;
import com.thisnode.ext.notes.util.NoteFilePaths;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

@Repository
@Slf4j
@NoArgsConstructor
public class NoteRepository {

    public String insert(String title, String type) {
        String noteMetaId = IdUtil.randomUUID();
        Path path = NoteFilePaths.noteMetaFilePath(noteMetaId);
        NoteMeta noteMeta = NoteMeta.newNoteMeta(noteMetaId, title, type, null);
        JsonFileUtils.write(path, noteMeta);
        return noteMetaId;
    }

    public void delete(String noteMetaId) {
        NoteMeta note = getOneNoteMeta(noteMetaId);
        if (note == null) {
            return;
        }
        for (String cardPath : note.getCardPaths()) {
            deleteCardFiles(NoteFilePaths.root().resolve(cardPath));
        }
        deleteQuietly(NoteFilePaths.noteMetaFilePath(noteMetaId));
    }

    public record CardData(CardMeta meta, String content) {
    }

    public CardData readCard(Path file) {
        String raw = readText(file);
        if (raw == null) {
            return null;
        }
        return new CardData(CardFile.metaOf(raw), CardFile.bodyOf(raw));
    }

    /** 以 CardMeta.id 为依据扫描卡片文件，找出对应文件路径。 */
    private Path cardFileById(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        for (String relative : listCardFiles()) {
            Path file = NoteFilePaths.root().resolve(relative);
            CardData data = readCard(file);
            if (data != null && data.meta() != null && cardId.equals(data.meta().id())) {
                return file;
            }
        }
        return null;
    }

    private List<Path> cardDirectories() {
        return List.of(NoteFilePaths.cardsDir(), NoteFilePaths.root().resolve("cards"))
                .stream().distinct().toList();
    }

    /** 扫描当前与旧目录布局下的 Markdown 卡片文件，相对路径供清单保存。 */
    private List<String> listCardFiles() {
        List<String> paths = new ArrayList<>();
        for (Path cardsDir : cardDirectories()) {
            if (!Files.isDirectory(cardsDir)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(cardsDir)) {
                for (Path file : stream.filter(Files::isRegularFile).toList()) {
                    if (NoteFilePaths.isContentFile(file)) {
                        paths.add(NoteFilePaths.relative(file));
                    }
                }
            } catch (IOException e) {
                log.warn("卡片目录无法遍历：{}（{}）", NoteFilePaths.relative(cardsDir), e.toString());
            }
        }
        return paths;
    }

    public List<NoteMeta> findAll() {
        Path metaDir = NoteFilePaths.noteMetaDir();
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
                    log.warn("元数据无法解析，已跳过：{}（{}）", NoteFilePaths.relative(file), e.toString());
                    continue;
                }
                if (meta == null) {
                    continue;
                }
                if (meta.getId() == null || meta.getId().isBlank()) {
                    log.warn("{} 不是笔记元数据（缺 id），已跳过", NoteFilePaths.relative(file));
                    continue;
                }
                notes.add(meta);
            }
        } catch (IOException | DirectoryIteratorException e) {
            log.warn("笔记元数据目录无法读取：{}（{}）", NoteFilePaths.relative(metaDir), e.toString());
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
        Path file = NoteFilePaths.noteMetaDir().resolve(noteMetaId + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return JsonFileUtils.read(file, NoteMeta.class);
        } catch (IOException | RuntimeException e) {
            log.warn("元数据无法解析，已跳过：{}（{}）", NoteFilePaths.relative(file), e.toString());
            return null;
        }
    }

    /**
     * 标题解析表（{@code 规范化标题 → 笔记 id}）：**每次现建**，不驻留。
     *
     * <p>重名时先到先得；回收站里的笔记不参与解析。数据库时代的唯一索引等价物。
     */
    public Map<String, String> titleIndex() {
        Map<String, String> index = new LinkedHashMap<>();
        List<NoteMeta> all = findAll();
        // 重名时先到先得。
        for (NoteMeta meta : all) {
            if (meta.getTitle() == null || meta.getTitle().isBlank()) {
                continue;
            }
            index.putIfAbsent(MarkdownUtils.normalizeTitle(meta.getTitle()), meta.getId());
        }
        return index;
    }

    /**
     * 一篇笔记的 markdown：它的卡片按清单顺序拼接（空白卡跳过，段落之间空一行）。
     *
     * <p>正文只有一份（在卡片里），所以笔记正文、搜索结果、导出永远一致。
     */
    public String noteMarkdown(String noteId) {
        StringBuilder joined = new StringBuilder();
        for (String relative : cardsOf(noteId)) {
            CardData data = readCard(NoteFilePaths.root().resolve(relative));
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

    /**
     * 解析 {@code [[标题]]}：现建解析表后查一次，查不到给 {@code null}。
     */
    public String resolveTitle(String target) {
        if (target == null || target.isBlank()) {
            return null;
        }
        return titleIndex().get(MarkdownUtils.normalizeTitle(target));
    }

    public Path writeCard(String noteTitle, CardMeta card, String contentMd, Path knownFile) {
        Path file = knownFile != null ? knownFile : resolveContentPath(noteTitle, NoteFilePaths.cardSlug(card.title(), contentMd), card.id());
        JsonFileUtils.writeText(file, CardFile.compose(card, contentMd));
        return file;
    }

    public void deleteCardFiles(Path contentFile) {
        deleteQuietly(contentFile);
    }

    public void ensureDirs() {
        try {
            for (Path dir : new Path[]{NoteFilePaths.cardsDir(), NoteFilePaths.noteMetaDir(), NoteFilePaths.ledgersDir()}) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建笔记目录结构 " + NoteFilePaths.root(), e);
        }
    }

    private Path resolveContentPath(String noteTitle, String cardSlug, String cardId) {
        String noteSlug = NoteFilePaths.cardSlug(noteTitle, null);
        String prefix = NoteFilePaths.cardFilePrefix(noteSlug, cardSlug);
        Path candidate = NoteFilePaths.cardFilePath(noteSlug, cardSlug, cardId);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        // 笔记名、卡片描述都一样（同名同描述的两张卡）：把随机串逐段加长，直到不撞车。
        String hex = cardId.replace("-", "");
        for (int len = 12; len < hex.length(); len += 4) {
            Path longer = NoteFilePaths.cardsDir().resolve(prefix + "-" + hex.substring(0, len) + NoteFilePaths.MD_SUFFIX);
            if (!Files.exists(longer)) {
                return longer;
            }
        }
        return NoteFilePaths.cardsDir().resolve(prefix + "-" + cardId + NoteFilePaths.MD_SUFFIX);
    }

    private String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("文件无法读取：{}（{}）", NoteFilePaths.relative(file), e.toString());
            return null;
        }
    }


    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除失败：{}（{}）", NoteFilePaths.relative(file), e.toString());
        }
    }

    // ================================================================ 笔记的标签（归属住在 meta 里）

    /** 一篇笔记的标签名（排序后）。 */
    public List<String> tagNames(String noteId) {
        NoteMeta meta = getOneNoteMeta(noteId);
        return meta == null ? List.of() : CollUtil.sort(new ArrayList<>(meta.getTags()), String.CASE_INSENSITIVE_ORDER);
    }

    /** 全库出现过的标签（含重复、保留原样大小写）：计数与"只在笔记里的标签"都从它现算。 */
    public List<String> allNoteTags() {
        List<String> out = new ArrayList<>();
        for (NoteMeta meta : findAll()) {
            out.addAll(meta.getTags());
        }
        return out;
    }

    /** 整篇替换标签归属（只改 meta；台账那一半由服务层登记）。 */
    public void setNodeTags(String noteId, List<String> names) {
        NoteMeta meta = getOneNoteMeta(noteId);
        if (meta == null) {
            return;
        }
        meta.setTags(Tag.cleanNames(names));
        meta.touch(System.currentTimeMillis());
        JsonFileUtils.write(NoteFilePaths.noteMetaFilePath(noteId), meta);
    }

    public void addNodeTag(String noteId, String name) {
        NoteMeta meta = getOneNoteMeta(noteId);
        String clean = Tag.normalizeName(name);
        if (meta == null || clean.isEmpty() || CollUtil.contains(meta.getTags(), existing -> StrUtil.equalsIgnoreCase(existing, clean))) {
            return;
        }
        List<String> next = new ArrayList<>(meta.getTags());
        next.add(clean);
        meta.setTags(next);
        meta.touch(System.currentTimeMillis());
        JsonFileUtils.write(NoteFilePaths.noteMetaFilePath(noteId), meta);
    }

    public void removeNodeTag(String noteId, String name) {
        NoteMeta meta = getOneNoteMeta(noteId);
        String clean = Tag.normalizeName(name);
        if (meta == null || clean.isEmpty()) {
            return;
        }
        List<String> next = new ArrayList<>();
        for (String tag : meta.getTags()) {
            if (!tag.equalsIgnoreCase(clean)) {
                next.add(tag);
            }
        }
        meta.setTags(next);
        meta.touch(System.currentTimeMillis());
        JsonFileUtils.write(NoteFilePaths.noteMetaFilePath(noteId), meta);
    }

    /** 改名：把所有笔记 meta 里的归属从 {@code from} 换成 {@code to}（台账那一半由服务层改）。 */
    public void renameTagInMetas(String from, String to) {
        for (NoteMeta meta : findAll()) {
            List<String> next = new ArrayList<>();
            boolean changed = false;
            for (String tag : meta.getTags()) {
                if (tag.equalsIgnoreCase(from)) {
                    next.add(to);
                    changed = true;
                } else {
                    next.add(tag);
                }
            }
            if (changed) {
                meta.setTags(Tag.cleanNames(next));
                meta.touch(System.currentTimeMillis());
                JsonFileUtils.write(NoteFilePaths.noteMetaFilePath(meta.getId()), meta);
            }
        }
    }

    // ================================================================ 卡片与视图编排（原 NoteCompositionService，纯原语部分）

    /** 一张卡片的归属；卡片 ID 仅由卡片文件头部的 {@link CardMeta#id()} 定义。 */
    public record Owned(NoteMeta note, String cardId, Path file) {
    }

    /** 一篇笔记的卡片相对路径清单（顺序即阅读顺序）；笔记不存在给空清单。 */
    private List<String> cardsOf(String noteId) {
        NoteMeta note = getOneNoteMeta(noteId);
        return note == null ? List.of() : note.getCardPaths();
    }

    /** 按 CardMeta.id 扫描各笔记登记的文件，确定归属。 */
    public Owned ownerOf(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        for (NoteMeta note : findAll()) {
            for (String relative : note.getCardPaths()) {
                Path file = NoteFilePaths.root().resolve(relative);
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

    /**
     * 一篇笔记的 markdown：它的卡片按清单顺序拼接（空白卡跳过，段落之间空一行）。
     *
     * <p>正文只有一份（在卡片里），所以笔记正文、搜索结果、导出永远一致。
     */

    /**
     * 写一张卡片（正文 + 头部注释）并把它登记进笔记清单。
     *
     * <p>{@code slot < 0} 表示追加；清单顺序就是阅读顺序，所以"在两卡之间插一张"只需插进清单，
     * 不必改动其他卡片。写入后顺带刷新这篇笔记的列表页缓存。
     */
    public CardMeta writeCard(String noteId, CardMeta card, String contentMd, int slot) {
        NoteMeta note = getOneNoteMeta(noteId);
        if (note == null) {
            return null;
        }
        List<String> paths = new ArrayList<>(note.getCardPaths());
        int at = indexOfCard(note, card.id());
        Path knownFile = at >= 0 ? NoteFilePaths.root().resolve(paths.get(at)) : null;
        Path written = writeCard(note.getTitle(), card, contentMd, knownFile);
        String relative = NoteFilePaths.relative(written);
        if (at >= 0) {
            paths.set(at, relative);
        } else if (slot >= 0 && slot < paths.size()) {
            paths.add(slot, relative);
        } else {
            paths.add(relative);
        }
        note.setCardPaths(paths);
        note.touch(System.currentTimeMillis());
        JsonFileUtils.write(NoteFilePaths.noteMetaFilePath(noteId), note);
        return card;
    }

    private int indexOfCard(NoteMeta note, String cardId) {
        for (int i = 0; i < note.getCardPaths().size(); i++) {
            CardData data = readCard(NoteFilePaths.root().resolve(note.getCardPaths().get(i)));
            if (data != null && data.meta() != null && cardId.equals(data.meta().id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 只改元数据（重写头部注释，正文原样保留）。
     */
    public void writeCardMeta(CardMeta card, Path contentFile) {
        String current = readText(contentFile);
        if (current == null) {
            JsonFileUtils.writeText(contentFile, CardFile.compose(card, ""));
            return;
        }
        JsonFileUtils.writeText(contentFile, CardFile.compose(card, CardFile.bodyOf(current)));
    }

    /**
     * 只改元数据（拖动 / 缩放 / 收起 / 插件扩展区）：重写头部注释，正文原样保留。
     */
    public CardMeta writeCardMeta(String cardId, CardMeta card) {
        if (card == null) {
            return null;
        }
        Path contentFile = cardFileById(cardId);
        if (contentFile == null) {
            return null;
        }
        String current = readText(contentFile);
        if (current == null) {
            JsonFileUtils.writeText(contentFile, CardFile.compose(card, ""));
            return card;
        }
        JsonFileUtils.writeText(contentFile, CardFile.compose(card, CardFile.bodyOf(current)));
        return card;
    }

    /** 删一张卡片：先摘出笔记清单，再删文件，最后刷新这篇笔记的列表页缓存。 */

    /**
     * 整篇替换正文：单卡笔记就是替换那张卡；多卡笔记把全文写进第一张卡、**清空其余卡片的内容**。
     *
     * <p>写正文的唯一落点（导入 Markdown、脚本改正文都走这里）。
     */
    public void replaceNoteContent(String noteId, String contentMd) {
        List<String> cards = cardsOf(noteId);
        String body = contentMd == null ? "" : contentMd;
        if (cards.isEmpty()) {
            long now = System.currentTimeMillis();
            writeCard(noteId, CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null, now), body, -1);
            return;
        }
        for (int i = 0; i < cards.size(); i++) {
            CardData data = readCard(NoteFilePaths.root().resolve(cards.get(i)));
            if (data == null || data.meta() == null) {
                continue;
            }
            writeCard(noteId, data.meta(), i == 0 ? body : "", i);
        }
    }

    /**
     * 一篇笔记的卡片数。
     */
    public int cardCount(String noteId) {
        return cardsOf(noteId).size();
    }

    /**
     * 一张卡片的元数据（找不到给 {@code null}）。
     */
    public CardMeta card(String cardId) {
        Owned owned = ownerOf(cardId);
        if (owned == null) {
            return null;
        }
        CardData data = readCard(owned.file());
        return data == null ? null : data.meta();
    }

    /**
     * 一篇笔记的全部卡片元数据，按清单顺序。
     */
    public List<CardMeta> cards(String noteId) {
        List<CardMeta> out = new ArrayList<>();
        for (String relative : cardsOf(noteId)) {
            CardData data = readCard(NoteFilePaths.root().resolve(relative));
            if (data != null && data.meta() != null) {
                out.add(data.meta());
            }
        }
        return out;
    }

    /**
     * 全部卡片元数据（维护/导出用；会读所有卡片文件）。
     */
    public List<CardMeta> allCards() {
        List<CardMeta> out = new ArrayList<>();
        for (NoteMeta note : findAll()) {
            for (String relative : note.getCardPaths()) {
                CardData data = readCard(NoteFilePaths.root().resolve(relative));
                if (data != null && data.meta() != null) {
                    out.add(data.meta());
                }
            }
        }
        return out;
    }

    /**
     * 卡片文件在磁盘上的路径（找不到给 {@code null}）。
     */
    public Path fileOf(String cardId) {
        Owned owned = ownerOf(cardId);
        return owned == null ? null : owned.file();
    }

    /**
     * 读卡片上的插件扩展区（没有就给空 map）。
     */
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

    /**
     * 写卡片上的插件扩展区。
     */
    public CardMeta putExtension(String cardId, String pluginId, Map<String, Object> value) {
        CardMeta card = card(cardId);
        if (card == null) {
            return null;
        }
        return writeCardMeta(cardId, card.withExtension(pluginId, value));
    }

    // ---- 与拆分前同名的方法（语义一致）：让调用方迁移只改注入类型，减少本批改动面 ----

    /**
     * 这张卡属于哪篇笔记（找不到给 {@code null}）。
     */
    public String noteIdOf(String cardId) {
        Owned owned = ownerOf(cardId);
        return owned == null ? null : owned.note().getId();
    }

    /**
     * 卡片正文；与 {@link #contentOf(String)} 同义。
     */
    public String content(String cardId) {
        return contentOf(cardId);
    }

    /**
     * 磁盘上的正文。拆分后正文本来就不常驻，所以它与 {@link #content(String)} 等价 ——
     * 保留这个名字，是为了让"保存前的冲突基线比对"这类调用点读起来仍然明确"以磁盘为准"。
     */
    public String contentOnDisk(String cardId) {
        return contentOf(cardId);
    }

    /** 写一张卡片（追加到清单末尾）。 */

    /** 写一张卡片，插进清单第 {@code slot} 位（{@code slot < 0} 表示追加）。 */

    /** 只改元数据；与 {@link #writeCardMeta(String, CardMeta)} 同义。 */

    /**
     * 磁盘上已经消失的卡片：把它从所属笔记的清单里摘掉（卡片跟着文件走）。
     *
     * <p>与认领互为对偶：外部删文件不必通知应用，下一次对账就对齐了。
     *
     * @return 摘掉的卡片数
     */
    public int dropMissingCards() {
        int dropped = 0;
        for (NoteMeta note : findAll()) {
            List<String> kept = new ArrayList<>();
            boolean changed = false;
            for (String relative : note.getCardPaths()) {
                Path cardFile = NoteFilePaths.root().resolve(relative);
                if (java.nio.file.Files.isRegularFile(cardFile)) {
                    kept.add(relative);
                } else {
                    changed = true;
                    dropped++;
                }
            }
            if (changed) {
                note.setCardPaths(kept);
                note.touch(System.currentTimeMillis());
                JsonFileUtils.write(NoteFilePaths.noteMetaFilePath(note.getId()), note);
            }
        }
        return dropped;
    }

    /**
     * 认领不在任何清单里的 md（手拷进来的、外部新建的）：它自己成为一篇单卡笔记。
     *
     * <p>按需扫描：每次现算"已知文件集合"，不做文件基线缓存。
     *
     * @return 认领的数量
     */
    public int claimUnmanaged() {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (NoteMeta note : findAll()) {
            known.addAll(note.getCardPaths());
        }
        int claimed = 0;
        for (String relative : listCardFiles()) {
            if (known.contains(relative)) {
                continue;
            }
            claim(relative);
            claimed++;
        }
        return claimed;
    }

    /**
     * 把一个游离的 md 认领成单卡笔记：补上卡片注释、建笔记 meta、算好列表页缓存。
     */
    private void claim(String relative) {
        Path file = NoteFilePaths.root().resolve(relative);
        CardData data = noteStoreRead(file);
        long now = System.currentTimeMillis();
        String content = data == null ? "" : data.content();
        CardMeta card = data != null && data.meta() != null ? data.meta() : CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null, now);
        String title = titleFromContent(content, file);
        // 认领的笔记也必须归属一个分组：直接落默认分组（不变量在创建点收口）。
        String noteId = IdUtil.randomUUID();
        NoteMeta note = NoteMeta.newNoteMeta(noteId, title, "note", NotebookBook.DEFAULT_GROUP_ID);
        note.setCardPaths(List.of(relative));
        JsonFileUtils.write(NoteFilePaths.noteMetaFilePath(noteId), note);
        writeCardMeta(card, file);
    }

    private CardData noteStoreRead(Path file) {
        return readCard(file);
    }

    /**
     * 标题兜底：正文首个标题 → 文件名（去掉 .md 与尾部随机串）→ "无标题笔记"。
     */
    private static String titleFromContent(String content, Path file) {
        String heading = MarkdownUtils.firstHeading(content);
        if (heading != null && !heading.isBlank()) {
            return heading;
        }
        String name = file.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(NoteFilePaths.MD_SUFFIX)) {
            name = name.substring(0, name.length() - NoteFilePaths.MD_SUFFIX.length());
        }
        name = name.replaceFirst("-[0-9a-fA-F]{8,}$", "");
        return name.isBlank() ? "无标题笔记" : name;
    }
}
