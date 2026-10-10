package com.thisnode.ext.notes.repository;

import com.alibaba.fastjson2.JSONFactory;
import com.thisnode.ext.notes.domain.*;
import com.thisnode.ext.notes.util.Ids;
import com.thisnode.ext.notes.util.JsonFiles;
import com.thisnode.ext.notes.util.Markdown;
import com.thisnode.ext.notes.util.Tags;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * 笔记与卡片的存储层：笔记 meta 与卡片文件在这里落盘，列表页与出入链的缓存也在这里刷新。
 *
 * <p>磁盘模型：一篇笔记是一份 {@code meta/<id>.json} 加一叠卡片 md（{@code cardRefs/}），卡片清单
 * 记在 meta 里。这里只提供读写原语——meta 的增删改查、卡片文件的拼装与读取、缓存的刷新——
 * **不做跨步骤编排**："写哪张卡、插到清单哪个位置"在 {@link CardRepository}，"建笔记先建首卡、
 * 删笔记先删卡"这类流程在 {@code NoteService}/{@code CardService}。
 *
 * <p>与数据库时代最大的不同：笔记**没有自己的正文存储**。{@code contentMd} 是读时由卡片按清单
 * 顺序拼出来的派生值，写正文等于写卡片。因此不存在"更新正文"的独立路径，也就不可能出现笔记
 * 正文与卡片两份内容不一致。
 */
@Repository
@RequiredArgsConstructor
public class NoteRepository {

    private final NoteFilePaths noteFilePaths;


    public String insert(String title, String type) {
        String id = Ids.newId();
        long now = System.currentTimeMillis();
        writeNoteMeta(NoteMeta.newNoteMeta(id, title, type, null, now));
        return id;
    }

    /**
     * 硬删除：卡片 md、sidecar 与笔记 meta 一起走（由组合层负责）。
     */
    public void delete(String id) {
        NoteMeta note = getOne(id);
        if (note == null) {
            return;
        }
        // 卡片文件与 meta 一起走。
        for (CardRef ref : note.cardRefs()) {
            deleteCardFiles(cardPath(ref));
        }
        deleteNoteMeta(id);
    }

    // ================================================================ 文件与卡片层（原 NoteStore）

    private static final Logger log = LoggerFactory.getLogger(NoteRepository.class);

    static {
        // 插件有自己的 classloader：平台侧对 JSONFactory 的全局设置不会传过来，所以这里自己关掉字母序，
        // 落盘的 meta 按字段声明序书写（与读的人看到的字段结构一致）。
        JSONFactory.setDefaultWriterAlphabetic(false);
    }

    public NoteFilePaths paths() {
        return noteFilePaths;
    }

    /**
     * 读一张卡片文件的结果。
     *
     * @param meta    {@code null} 表示这个 md 没有 meta 注释（需要认领）
     * @param content 正文（已剥掉头部 meta 注释）
     */
    public record CardData(CardMeta meta, String content) {
    }

    /**
     * 读一张卡片：注释里的 meta + 正文。懒加载与迁移都走它。
     */
    public CardData readCard(Path file) {
        String raw = readText(file);
        if (raw == null) {
            return null;
        }
        return new CardData(CardFile.metaOf(raw), CardFile.bodyOf(raw));
    }

    /**
     * 卡片文件在磁盘上的路径（{@code Card.file} 是相对路径）。
     */
    public Path cardPath(CardRef ref) {
        return ref == null ? null : noteFilePaths.root().resolve(ref.file());
    }

    /**
     * 读一张卡（元数据 + 正文）；ref 为空或文件不在时返回 {@code null}。
     */
    public CardData readCard(CardRef ref) {
        Path file = cardPath(ref);
        return file == null ? null : readCard(file);
    }

    /**
     * 只取卡片正文（文件不在或读不出来时给空串）。
     */
    public String cardContent(CardRef ref) {
        CardData data = readCard(ref);
        return data == null ? "" : data.content();
    }

    /**
     * 一次目录遍历的结果：卡片文件（相对路径 → mtime）。
     */
    public record FileListing(Map<String, Long> cards) {
    }

    /**
     * 遍历卡片目录一次，拿到卡片文件清单。
     *
     * <p>合并的理由是实测出来的：10000 个文件的一次 {@code Files.walk} 在这台机器上约 0.8s，
     * 分开扫三次就是启动时白花 1.6s。文件系统遍历是 O(卡片数) 的操作，**能少一次就少一次**。
     */
    public FileListing listFiles() {
        Map<String, Long> cards = new LinkedHashMap<>();
        Path cardsDir = noteFilePaths.cardsDir();
        if (!Files.isDirectory(cardsDir)) {
            return new FileListing(cards);
        }
        try (Stream<Path> stream = Files.walk(cardsDir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                if (!noteFilePaths.isContentFile(file)) {
                    continue;
                }
                try {
                    cards.put(noteFilePaths.relative(file), Files.getLastModifiedTime(file).toMillis());
                } catch (IOException e) {
                    log.debug("无法 stat {}：{}", noteFilePaths.relative(file), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("卡片目录无法遍历：{}（{}）", noteFilePaths.relative(cardsDir), e.toString());
        }
        return new FileListing(cards);
    }

    /**
     * 只取卡片文件清单（变更检测用）。
     */
    public Map<String, Long> listCardFiles() {
        return listFiles().cards();
    }

    public List<NoteMeta> findAll() {
        Path metaDir = noteFilePaths.noteMetaDir();
        if (!Files.isDirectory(metaDir)) {
            return Collections.emptyList();
        }

        List<NoteMeta> notes = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(metaDir, "*.json")) {
            for (Path file : stream) {
                NoteMeta meta = readMeta(file, NoteMeta.class);
                if (meta == null) {
                    continue;
                }
                if (meta.id() == null || meta.id().isBlank()) {
                    log.warn("{} 不是笔记元数据（缺 id），已跳过", noteFilePaths.relative(file));
                    continue;
                }
                notes.add(meta);
            }
        } catch (IOException | DirectoryIteratorException e) {
            log.warn("笔记元数据目录无法读取：{}（{}）", noteFilePaths.relative(metaDir), e.toString());
        }
        return notes;
    }

    /**
     * 只读一篇笔记的 meta（文件不在或读不出来给 {@code null}）—— 不会为了它扫全库。
     */
    public NoteMeta getOne(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return null;
        }
        Path file = noteFilePaths.noteMetaDir().resolve(noteId + ".json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return readMeta(file, NoteMeta.class);
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
            if (meta.title() == null || meta.title().isBlank()) {
                continue;
            }
            index.putIfAbsent(Markdown.normalizeTitle(meta.title()), meta.id());
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
        for (CardRef ref : cardsOf(noteId)) {
            String part = cardContent(ref);
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
        return titleIndex().get(Markdown.normalizeTitle(target));
    }

    // ------------------------------------------------------------------ write

    public void writeNoteMeta(NoteMeta note) {
        writeJson(noteFilePaths.noteMeta(note.id()), note);
    }

    /**
     * 写一张卡片：meta 注释 + 正文，都在这一个 md 文件里。
     *
     * <p>文件名 = {@code <笔记名>-<卡片描述>-<随机串>.md}（见 {@link NoteFilePaths#cardFilePrefix}）。
     * {@code knownFile} 非空表示这张卡已经在盘上了（只改内容）——**沿用原路径**，绝不因为标题变了
     * 就改名：文件名只是给人看的，身份在 meta 的 id 里。
     */
    public Path writeCard(String noteTitle, CardMeta card, String contentMd, Path knownFile) {
        Path file = knownFile != null ? knownFile : resolveContentPath(noteTitle, NoteFilePaths.cardSlug(card.title(), contentMd), card.id());
        writeText(file, CardFile.compose(card, contentMd));
        return file;
    }

    /** 只改元数据（拖动、改宽度、插件扩展区）：重写头部注释，正文原样保留。 */

    /**
     * 删一张卡片：md 为主，顺手清掉旧版本留下的 sidecar（如果还有）。
     */
    public void deleteCardFiles(Path contentFile) {
        deleteQuietly(contentFile);
        removeLegacySidecar(contentFile);
    }

    /**
     * 清掉旧版本留下的 sidecar（{@code .meta.json}）。
     *
     * <p>meta 现在写在 md 头部注释里、且注释是唯一权威 —— 留着 sidecar 就是两份 meta 并存，
     * 日后总有人（脚本、旧版本、手工）把它当真。所以读到就删，而不是留着等用户清理。
     */
    public void removeLegacySidecar(Path contentFile) {
        deleteQuietly(NoteFilePaths.sidecarFor(contentFile));
    }

    public void deleteNoteMeta(String noteId) {
        deleteQuietly(noteFilePaths.noteMeta(noteId));
    }

    /**
     * 首次使用的目录要建出来，否则扫描与后面的写入都会落空。
     */
    public void ensureDirs() {
        try {
            for (Path dir : new Path[]{noteFilePaths.cardsDir(), noteFilePaths.noteMetaDir(), noteFilePaths.ledgersDir()}) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建笔记目录结构 " + noteFilePaths.root(), e);
        }
    }

    /**
     * 决定一张新卡片落在哪个文件：{@code <slug>-<shortId>.md}。同名文件已存在时先看它是不是同一张卡
     * （重扫后重写的情况），不是就把短 id 逐段加长，实在不行用完整 id 兜底。
     */
    private Path resolveContentPath(String noteTitle, String cardSlug, String cardId) {
        String noteSlug = NoteFilePaths.cardSlug(noteTitle, null);
        String prefix = NoteFilePaths.cardFilePrefix(noteSlug, cardSlug);
        Path candidate = noteFilePaths.cardFilePath(noteSlug, cardSlug, cardId);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        // 笔记名、卡片描述都一样（同名同描述的两张卡）：把随机串逐段加长，直到不撞车。
        String hex = cardId.replace("-", "");
        for (int len = 12; len < hex.length(); len += 4) {
            Path longer = noteFilePaths.cardsDir().resolve(prefix + "-" + hex.substring(0, len) + NoteFilePaths.MD_SUFFIX);
            if (!Files.exists(longer)) {
                return longer;
            }
        }
        return noteFilePaths.cardsDir().resolve(prefix + "-" + cardId + NoteFilePaths.MD_SUFFIX);
    }

    // ------------------------------------------------------------------- text

    public String readText(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("文件无法读取：{}（{}）", noteFilePaths.relative(file), e.toString());
            return null;
        }
    }

    public void writeText(Path file, String text) {
        JsonFiles.writeText(file, text);
    }

    public void writeJson(Path file, Object value) {
        JsonFiles.write(file, value);
    }

    public <T> T readMeta(Path file, Class<T> type) {
        try {
            return JsonFiles.read(file, type);
        } catch (IOException | RuntimeException e) {
            if (Files.exists(file)) {
                log.warn("元数据无法解析，已跳过：{}（{}）", noteFilePaths.relative(file), e.toString());
            }
            return null;
        }
    }

    public void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("删除失败：{}（{}）", noteFilePaths.relative(file), e.toString());
        }
    }

    // ================================================================ 笔记的标签（归属住在 meta 里）

    /** 一篇笔记的标签名（排序后）。 */
    public List<String> tagNames(String noteId) {
        NoteMeta meta = getOne(noteId);
        return meta == null ? List.of() : Tags.sorted(meta.tags());
    }

    /** 多篇笔记的标签名（只读 meta）。 */
    public Map<String, List<String>> tagNamesOfAll(List<String> noteIds) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (noteIds == null) {
            return map;
        }
        for (String id : noteIds) {
            NoteMeta meta = getOne(id);
            map.put(id, meta == null ? List.of() : Tags.sorted(meta.tags()));
        }
        return map;
    }

    /** 全库出现过的标签（含重复、保留原样大小写）：计数与"只在笔记里的标签"都从它现算。 */
    public List<String> allNoteTags() {
        List<String> out = new ArrayList<>();
        for (NoteMeta meta : findAll()) {
            out.addAll(meta.tags());
        }
        return out;
    }

    /** 整篇替换标签归属（只改 meta；台账那一半由服务层登记）。 */
    public void setNodeTags(String noteId, List<String> names) {
        NoteMeta meta = getOne(noteId);
        if (meta == null) {
            return;
        }
        writeNoteMeta(meta.withTags(Tags.cleaned(names)));
    }

    public void addNodeTag(String noteId, String name) {
        NoteMeta meta = getOne(noteId);
        String clean = Tags.normalize(name);
        if (meta == null || clean.isEmpty() || Tags.contains(meta.tags(), clean)) {
            return;
        }
        List<String> next = new ArrayList<>(meta.tags());
        next.add(clean);
        writeNoteMeta(meta.withTags(next));
    }

    public void removeNodeTag(String noteId, String name) {
        NoteMeta meta = getOne(noteId);
        String clean = Tags.normalize(name);
        if (meta == null || clean.isEmpty()) {
            return;
        }
        List<String> next = new ArrayList<>();
        for (String tag : meta.tags()) {
            if (!tag.equalsIgnoreCase(clean)) {
                next.add(tag);
            }
        }
        writeNoteMeta(meta.withTags(next));
    }

    /** 改名：把所有笔记 meta 里的归属从 {@code from} 换成 {@code to}（台账那一半由服务层改）。 */
    public void renameTagInMetas(String from, String to) {
        for (NoteMeta meta : findAll()) {
            List<String> next = new ArrayList<>();
            boolean changed = false;
            for (String tag : meta.tags()) {
                if (tag.equalsIgnoreCase(from)) {
                    next.add(to);
                    changed = true;
                } else {
                    next.add(tag);
                }
            }
            if (changed) {
                writeNoteMeta(meta.withTags(Tags.cleaned(next)));
            }
        }
    }

    // ================================================================ 卡片与视图编排（原 NoteCompositionService，纯原语部分）

    /**
     * 一张卡片的归属：它属于哪篇笔记、对应清单里的哪一项。
     */
    public record Owned(NoteMeta note, CardRef ref) {
    }


    /**
     * 一篇笔记的卡片清单（顺序即阅读顺序）；笔记不存在给空清单。
     */
    public List<CardRef> cardsOf(String noteId) {
        NoteMeta note = getOne(noteId);
        return note == null ? List.of() : note.cardRefs();
    }

    /**
     * 按 cardId 找归属：扫一遍笔记 meta，看哪篇的清单里有它。
     *
     * <p>归属信息本身存在笔记 meta 的清单里，所以这里不需要任何常驻映射。
     */
    public Owned ownerOf(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        for (NoteMeta note : findAll()) {
            for (CardRef ref : note.cardRefs()) {
                if (ref.id().equals(cardId)) {
                    return new Owned(note, ref);
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
        return owned == null ? "" : cardContent(owned.ref());
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
        NoteMeta note = getOne(noteId);
        if (note == null) {
            return null;
        }
        CardRef existing = refOf(note, card.id());
        Path written = writeCard(note.title(), card, contentMd, cardPath(existing));
        CardRef ref = new CardRef(card.id(), paths().relative(written));

        List<CardRef> cards = new ArrayList<>(note.cardRefs());
        int at = indexOf(cards, card.id());
        if (at >= 0) {
            cards.set(at, ref);
        } else if (slot >= 0 && slot < cards.size()) {
            cards.add(slot, ref);
        } else {
            cards.add(ref);
        }
        writeNoteMeta(note.withCards(cards).touched(System.currentTimeMillis()));
        return card;
    }

    private static CardRef refOf(NoteMeta note, String cardId) {
        for (CardRef ref : note.cardRefs()) {
            if (ref.id().equals(cardId)) {
                return ref;
            }
        }
        return null;
    }

    private static int indexOf(List<CardRef> cards, String cardId) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).id().equals(cardId)) {
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
            writeText(contentFile, CardFile.compose(card, ""));
            return;
        }
        writeText(contentFile, CardFile.compose(card, CardFile.bodyOf(current)));
    }

    /**
     * 只改元数据（拖动 / 缩放 / 收起 / 插件扩展区）：重写头部注释，正文原样保留。
     */
    public CardMeta writeCardMeta(String cardId, CardMeta card) {
        if (card == null) {
            return null;
        }
        CardRef ref = refOfId(cardId);
        if (ref == null) {
            return null;
        }
        Path contentFile = cardPath(ref);
        String current = readText(contentFile);
        if (current == null) {
            writeText(contentFile, CardFile.compose(card, ""));
            return card;
        }
        writeText(contentFile, CardFile.compose(card, CardFile.bodyOf(current)));
        return card;
    }

    /** 删一张卡片：先摘出笔记清单，再删文件，最后刷新这篇笔记的列表页缓存。 */

    /**
     * 整篇替换正文：单卡笔记就是替换那张卡；多卡笔记把全文写进第一张卡、**清空其余卡片的内容**。
     *
     * <p>写正文的唯一落点（导入 Markdown、脚本改正文都走这里）。
     */
    public void replaceNoteContent(String noteId, String contentMd) {
        List<CardRef> cards = cardsOf(noteId);
        String body = contentMd == null ? "" : contentMd;
        if (cards.isEmpty()) {
            long now = System.currentTimeMillis();
            writeCard(noteId, CardMeta.newCardMeta(Ids.newId(), 0, 0, "", null, null, now), body, -1);
            return;
        }
        for (int i = 0; i < cards.size(); i++) {
            CardData data = readCard(cards.get(i));
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
     * 全部笔记的卡片数（noteId → 张数）。
     */
    public Map<String, Integer> cardCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (NoteMeta note : findAll()) {
            out.put(note.id(), note.cardRefs().size());
        }
        return out;
    }

    /**
     * cardId → 清单项。
     */
    private CardRef refOfId(String cardId) {
        Owned owned = ownerOf(cardId);
        return owned == null ? null : owned.ref();
    }

    /**
     * 一张卡片的元数据（找不到给 {@code null}）。
     */
    public CardMeta card(String cardId) {
        Owned owned = ownerOf(cardId);
        if (owned == null) {
            return null;
        }
        CardData data = readCard(owned.ref());
        return data == null ? null : data.meta();
    }

    /**
     * 一篇笔记的全部卡片元数据，按清单顺序。
     */
    public List<CardMeta> cards(String noteId) {
        List<CardMeta> out = new ArrayList<>();
        for (CardRef ref : cardsOf(noteId)) {
            CardData data = readCard(ref);
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
            for (CardRef ref : note.cardRefs()) {
                CardData data = readCard(ref);
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
        return owned == null ? null : cardPath(owned.ref());
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
        return owned == null ? null : owned.note().id();
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
            List<CardRef> kept = new ArrayList<>();
            boolean changed = false;
            for (CardRef ref : note.cardRefs()) {
                if (java.nio.file.Files.isRegularFile(cardPath(ref))) {
                    kept.add(ref);
                } else {
                    changed = true;
                    dropped++;
                }
            }
            if (changed) {
                writeNoteMeta(note.withCards(kept).touched(System.currentTimeMillis()));
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
            for (CardRef ref : note.cardRefs()) {
                known.add(ref.file());
            }
        }
        int claimed = 0;
        for (String relative : listCardFiles().keySet()) {
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
        Path file = paths().root().resolve(relative);
        CardData data = noteStoreRead(file);
        long now = System.currentTimeMillis();
        String content = data == null ? "" : data.content();
        CardMeta card = data != null && data.meta() != null ? data.meta() : CardMeta.newCardMeta(Ids.newId(), 0, 0, "", null, null, now);
        CardRef ref = new CardRef(card.id(), relative);
        String title = titleFromContent(content, file);
        // 认领的笔记也必须归属一个分组：直接落默认分组（不变量在创建点收口）。
        writeNoteMeta(NoteMeta.newNoteMeta(Ids.newId(), title, "note", NotebookBook.DEFAULT_GROUP_ID, now).withCards(List.of(ref)));
        writeCardMeta(card, cardPath(ref));
    }

    private CardData noteStoreRead(Path file) {
        return readCard(new CardRef("", paths().relative(file)));
    }

    /**
     * 标题兜底：正文首个标题 → 文件名（去掉 .md 与尾部随机串）→ "无标题笔记"。
     */
    private static String titleFromContent(String content, Path file) {
        String heading = Markdown.firstHeading(content);
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
