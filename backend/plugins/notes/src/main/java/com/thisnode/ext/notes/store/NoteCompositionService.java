package com.thisnode.ext.notes.store;

import com.thisnode.ext.notes.util.Ids;
import com.thisnode.ext.notes.util.Markdown;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * **组合层**：唯一同时编排「笔记 meta」与「卡片文件」的地方（文件读写都在 {@link NoteStore}）。
 *
 * <p>「这篇笔记的卡片按顺序拼出正文」「这张卡属于哪篇笔记」「写完卡片后刷新列表页缓存」——
 * 跨两个域的多步不变量都收拢在这里。
 *
 * <p>无状态：每次调用现读磁盘；一次方法内部需要多次用到笔记集合时，自己 {@code scanNotes()} 一次后在
 * 局部变量里传递（局部变量不是缓存）。
 */
@Component
public class NoteCompositionService {

    private final NoteStore noteStore;

    public NoteCompositionService(NoteStore noteStore) {
        this.noteStore = noteStore;
    }

    /** 一张卡片的归属：它属于哪篇笔记、对应清单里的哪一项。 */
    public record Owned(NoteMeta note, CardRef ref) {
    }

    /**
     * 建一篇新笔记：先落 meta，再建首卡（"一篇笔记至少一张卡"的不变量在这里落地）。
     *
     * <p>笔记仓储只交笔记域的数据，卡片细节由组合层处理。
     */
    public void createNote(NoteMeta meta, String contentMd) {
        noteStore.writeNoteMeta(meta);
        long now = System.currentTimeMillis();
        writeCard(meta.id(), BlockMeta.fresh(Ids.newId(), 0, 0, "", null, null, now),
                contentMd == null ? "" : contentMd, -1);
    }

    /** 一篇笔记的卡片清单（顺序即阅读顺序）；笔记不存在给空清单。 */
    public List<CardRef> cardsOf(String noteId) {
        NoteMeta note = noteStore.note(noteId);
        return note == null ? List.of() : note.cards();
    }

    /**
     * 按 blockId 找归属：扫一遍笔记 meta，看哪篇的清单里有它。
     *
     * <p>归属信息本身存在笔记 meta 的清单里，所以这里不需要任何常驻映射。
     */
    public Owned ownerOf(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        for (NoteMeta note : noteStore.scanNotes()) {
            for (CardRef ref : note.cards()) {
                if (ref.id().equals(blockId)) {
                    return new Owned(note, ref);
                }
            }
        }
        return null;
    }

    /** 一张卡片的正文（找不到归属或文件不在时给空串）。 */
    public String contentOf(String blockId) {
        Owned owned = ownerOf(blockId);
        return owned == null ? "" : noteStore.cardContent(owned.ref());
    }

    /**
     * 一篇笔记的 markdown：它的卡片按清单顺序拼接（空白卡跳过，段落之间空一行）。
     *
     * <p>正文只有一份（在卡片里），所以笔记正文、搜索结果、导出永远一致。
     */
    public String noteMarkdown(String noteId) {
        StringBuilder joined = new StringBuilder();
        for (CardRef ref : cardsOf(noteId)) {
            String part = noteStore.cardContent(ref);
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
     * 写一张卡片（正文 + 头部注释）并把它登记进笔记清单。
     *
     * <p>{@code slot < 0} 表示追加；清单顺序就是阅读顺序，所以"在两卡之间插一张"只需插进清单，
     * 不必改动其他卡片。写入后顺带刷新这篇笔记的列表页缓存。
     */
    public BlockMeta writeCard(String noteId, BlockMeta block, String contentMd, int slot) {
        NoteMeta note = noteStore.note(noteId);
        if (note == null) {
            return null;
        }
        CardRef existing = refOf(note, block.id());
        Path written = noteStore.writeBlock(note.title(), block, contentMd, noteStore.cardPath(existing));
        CardRef ref = CardRef.of(block.id(), noteStore.paths().relative(written));

        List<CardRef> cards = new ArrayList<>(note.cards());
        int at = indexOf(cards, block.id());
        if (at >= 0) {
            cards.set(at, ref);
        } else if (slot >= 0 && slot < cards.size()) {
            cards.add(slot, ref);
        } else {
            cards.add(ref);
        }
        noteStore.writeNoteMeta(note.withCards(cards).touched(System.currentTimeMillis()));
        refreshCachedView(noteId);
        return block;
    }

    /**
     * 刷新一篇笔记 meta 里的列表页缓存：摘要 + 出链清单/计数 + 未解析数，并把入链数的变化按
     * 「旧清单 → 新清单」的差集落到对端。
     *
     * <p>标题解析表一次现建、整批复用（不是缓存：这个方法返回即丢）。
     */
    public void refreshCachedView(String noteId) {
        NoteMeta meta = noteStore.note(noteId);
        if (meta == null) {
            return;
        }
        String markdown = noteMarkdown(noteId);
        Map<String, String> titles = noteStore.titleIndex();
        List<LinkRef> links = new ArrayList<>();
        int unresolved = 0;
        for (Markdown.WikiLink link : Markdown.wikiLinks(markdown)) {
            String to = titles.get(Markdown.normalizeTitle(link.target()));
            if (to == null) {
                unresolved++;
            }
            links.add(LinkRef.of(link.target(), link.label(), to));
        }
        applyInboundDelta(meta.links(), links);
        NoteMeta next = meta.withCachedView(Markdown.plainPreview(markdown, 160), links,
                links.size(), meta.inboundCount(), unresolved);
        if (!next.equals(meta)) {
            noteStore.writeNoteMeta(next);
        }
    }

    /** 按「旧出链 → 新出链」的差集，给对端笔记的入链数 ±1。 */
    public void applyInboundDelta(List<LinkRef> oldLinks, List<LinkRef> newLinks) {
        Map<String, Integer> delta = new LinkedHashMap<>();
        for (LinkRef link : oldLinks) {
            if (link.to() != null) {
                delta.merge(link.to(), -1, Integer::sum);
            }
        }
        for (LinkRef link : newLinks) {
            if (link.to() != null) {
                delta.merge(link.to(), 1, Integer::sum);
            }
        }
        delta.forEach((targetId, d) -> {
            if (d == 0) {
                return;
            }
            NoteMeta target = noteStore.note(targetId);
            if (target != null) {
                noteStore.writeNoteMeta(target.withInboundCount(target.inboundCount() + d));
            }
        });
    }

    private static CardRef refOf(NoteMeta note, String blockId) {
        for (CardRef ref : note.cards()) {
            if (ref.id().equals(blockId)) {
                return ref;
            }
        }
        return null;
    }

    private static int indexOf(List<CardRef> cards, String blockId) {
        for (int i = 0; i < cards.size(); i++) {
            if (cards.get(i).id().equals(blockId)) {
                return i;
            }
        }
        return -1;
    }

    /** 只改元数据（拖动 / 缩放 / 收起 / 插件扩展区）：重写头部注释，正文原样保留。 */
    public BlockMeta writeCardMeta(String blockId, BlockMeta block) {
        if (block == null) {
            return null;
        }
        CardRef ref = refOfId(blockId);
        if (ref == null) {
            return null;
        }
        noteStore.writeBlockMeta(block, noteStore.cardPath(ref));
        return block;
    }

    /** 删一张卡片：先摘出笔记清单，再删文件，最后刷新这篇笔记的列表页缓存。 */
    public void removeCard(String blockId) {
        Owned owned = ownerOf(blockId);
        if (owned == null) {
            return;
        }
        noteStore.deleteBlockFiles(noteStore.cardPath(owned.ref()));
        List<CardRef> cards = new ArrayList<>(owned.note().cards());
        cards.removeIf(ref -> ref.id().equals(blockId));
        noteStore.writeNoteMeta(owned.note().withCards(cards).touched(System.currentTimeMillis()));
        refreshCachedView(owned.note().id());
    }

    /** 硬删除一篇笔记：它的卡片文件与 meta 一起走，并释放它对别人的入链。 */
    public void removeNote(String noteId) {
        NoteMeta note = noteStore.note(noteId);
        if (note == null) {
            return;
        }
        applyInboundDelta(note.links(), List.of());
        for (CardRef ref : note.cards()) {
            noteStore.deleteBlockFiles(noteStore.cardPath(ref));
        }
        noteStore.deleteNoteMeta(noteId);
    }

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
            writeCard(noteId, BlockMeta.fresh(Ids.newId(), 0, 0, "", null, null, now), body, -1);
            return;
        }
        for (int i = 0; i < cards.size(); i++) {
            NoteStore.CardData data = noteStore.readCard(cards.get(i));
            if (data == null || data.meta() == null) {
                continue;
            }
            writeCard(noteId, data.meta(), i == 0 ? body : "", i);
        }
    }

    /** 一篇笔记的卡片数。 */
    public int blockCount(String noteId) {
        return cardsOf(noteId).size();
    }

    /** 全部笔记的卡片数（noteId → 张数）。 */
    public Map<String, Integer> blockCounts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (NoteMeta note : noteStore.scanNotes()) {
            out.put(note.id(), note.cards().size());
        }
        return out;
    }

    /**
     * 「先写链接、后建笔记」：把各篇里 {@code to == null} 且目标匹配本文标题/别名的出链补上解析结果。
     *
     * @return 被重算的笔记数
     */
    public int resolvePendingLinks(String targetNoteId, java.util.Set<String> normalizedTargets) {
        if (normalizedTargets == null || normalizedTargets.isEmpty()) {
            return 0;
        }
        int healed = 0;
        for (NoteMeta note : noteStore.scanNotes()) {
            if (note.id().equals(targetNoteId) || note.links().isEmpty()) {
                continue;
            }
            boolean pending = false;
            for (LinkRef link : note.links()) {
                if (link.to() == null
                        && normalizedTargets.contains(Markdown.normalizeTitle(link.target()))) {
                    pending = true;
                    break;
                }
            }
            if (pending) {
                refreshCachedView(note.id());
                healed++;
            }
        }
        return healed;
    }

    /** 维护用：按正文重算**所有**笔记的列表页缓存。 */
    public int refreshAllCachedViews() {
        int done = 0;
        for (NoteMeta note : noteStore.scanNotes()) {
            refreshCachedView(note.id());
            done++;
        }
        return done;
    }

    /** 按各篇 meta 里缓存的出链**全量校准**入链数（只读 meta，不碰正文）。 */
    public int recalibrateInbound() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<NoteMeta> all = noteStore.scanNotes();
        for (NoteMeta note : all) {
            for (LinkRef link : note.links()) {
                if (link.to() != null) {
                    counts.merge(link.to(), 1, Integer::sum);
                }
            }
        }
        int fixed = 0;
        for (NoteMeta note : all) {
            int want = counts.getOrDefault(note.id(), 0);
            if (note.inboundCount() != want) {
                noteStore.writeNoteMeta(note.withInboundCount(want));
                fixed++;
            }
        }
        return fixed;
    }

    /** blockId → 清单项。 */
    private CardRef refOfId(String blockId) {
        Owned owned = ownerOf(blockId);
        return owned == null ? null : owned.ref();
    }

    /** 一张卡片的元数据（找不到给 {@code null}）。 */
    public BlockMeta block(String blockId) {
        Owned owned = ownerOf(blockId);
        if (owned == null) {
            return null;
        }
        NoteStore.CardData data = noteStore.readCard(owned.ref());
        return data == null ? null : data.meta();
    }

    /** 一篇笔记的全部卡片元数据，按清单顺序。 */
    public List<BlockMeta> blocks(String noteId) {
        List<BlockMeta> out = new ArrayList<>();
        for (CardRef ref : cardsOf(noteId)) {
            NoteStore.CardData data = noteStore.readCard(ref);
            if (data != null && data.meta() != null) {
                out.add(data.meta());
            }
        }
        return out;
    }

    /** 全部卡片元数据（维护/导出用；会读所有卡片文件）。 */
    public List<BlockMeta> allBlocks() {
        List<BlockMeta> out = new ArrayList<>();
        for (NoteMeta note : noteStore.scanNotes()) {
            for (CardRef ref : note.cards()) {
                NoteStore.CardData data = noteStore.readCard(ref);
                if (data != null && data.meta() != null) {
                    out.add(data.meta());
                }
            }
        }
        return out;
    }

    /** 卡片文件在磁盘上的路径（找不到给 {@code null}）。 */
    public Path fileOf(String blockId) {
        Owned owned = ownerOf(blockId);
        return owned == null ? null : noteStore.cardPath(owned.ref());
    }

    /** 列表页摘要：直接取 meta 里写好的缓存，不读卡片。 */
    public String summary(String noteId) {
        NoteMeta note = noteStore.note(noteId);
        return note == null ? "" : note.summary();
    }

    /** 读卡片上的插件扩展区（没有就给空 map）。 */
    public Map<String, Object> extension(String blockId, String pluginId) {
        BlockMeta block = block(blockId);
        if (block == null) {
            return Map.of();
        }
        Object value = block.ext().get(pluginId);
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    /** 写卡片上的插件扩展区。 */
    public BlockMeta putExtension(String blockId, String pluginId, Map<String, Object> value) {
        BlockMeta block = block(blockId);
        if (block == null) {
            return null;
        }
        return writeCardMeta(blockId, block.withExtension(pluginId, value));
    }

    // ---- 与拆分前同名的方法（语义一致）：让调用方迁移只改注入类型，减少本批改动面 ----

    /** 这张卡属于哪篇笔记（找不到给 {@code null}）。 */
    public String noteIdOf(String blockId) {
        Owned owned = ownerOf(blockId);
        return owned == null ? null : owned.note().id();
    }

    /** 卡片正文；与 {@link #contentOf(String)} 同义。 */
    public String content(String blockId) {
        return contentOf(blockId);
    }

    /**
     * 磁盘上的正文。拆分后正文本来就不常驻，所以它与 {@link #content(String)} 等价 ——
     * 保留这个名字，是为了让"保存前的冲突基线比对"这类调用点读起来仍然明确"以磁盘为准"。
     */
    public String contentOnDisk(String blockId) {
        return contentOf(blockId);
    }

    /** 写一张卡片（追加到清单末尾）。 */
    public BlockMeta putBlock(String noteId, BlockMeta block, String contentMd) {
        return writeCard(noteId, block, contentMd, -1);
    }

    /** 写一张卡片，插进清单第 {@code slot} 位（{@code slot < 0} 表示追加）。 */
    public BlockMeta putBlock(String noteId, BlockMeta block, String contentMd, int slot) {
        return writeCard(noteId, block, contentMd, slot);
    }

    /** 只改元数据；与 {@link #writeCardMeta(String, BlockMeta)} 同义。 */
    public BlockMeta putBlockMeta(BlockMeta block) {
        return block == null ? null : writeCardMeta(block.id(), block);
    }

    /** 删一张卡片；与 {@link #removeCard(String)} 同义。 */
    public void removeBlock(String blockId) {
        removeCard(blockId);
    }

    /**
     * 磁盘上已经消失的卡片：把它从所属笔记的清单里摘掉（卡片跟着文件走）。
     *
     * <p>与认领互为对偶：外部删文件不必通知应用，下一次对账就对齐了。
     *
     * @return 摘掉的卡片数
     */
    public int dropMissingCards() {
        int dropped = 0;
        for (NoteMeta note : noteStore.scanNotes()) {
            List<CardRef> kept = new ArrayList<>();
            boolean changed = false;
            for (CardRef ref : note.cards()) {
                if (java.nio.file.Files.isRegularFile(noteStore.cardPath(ref))) {
                    kept.add(ref);
                } else {
                    changed = true;
                    dropped++;
                }
            }
            if (changed) {
                noteStore.writeNoteMeta(note.withCards(kept).touched(System.currentTimeMillis()));
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
        for (NoteMeta note : noteStore.scanNotes()) {
            for (CardRef ref : note.cards()) {
                known.add(ref.file());
            }
        }
        int claimed = 0;
        for (String relative : noteStore.listCardFiles().keySet()) {
            if (known.contains(relative)) {
                continue;
            }
            claim(relative);
            claimed++;
        }
        return claimed;
    }

    /** 把一个游离的 md 认领成单卡笔记：补上卡片注释、建笔记 meta、算好列表页缓存。 */
    private void claim(String relative) {
        Path file = noteStore.paths().root().resolve(relative);
        NoteStore.CardData data = noteStoreRead(file);
        long now = System.currentTimeMillis();
        String content = data == null ? "" : data.content();
        BlockMeta block = data != null && data.meta() != null
                ? data.meta()
                : BlockMeta.fresh(Ids.newId(), 0, 0, "", null, null, now);
        CardRef ref = CardRef.of(block.id(), relative);
        String title = titleFromContent(content, file);
        // 认领的笔记也必须归属一个分组：直接落默认分组（不变量在创建点收口）。
        noteStore.writeNoteMeta(NoteMeta.fresh(Ids.newId(), title, "note", null, null, null,
                NotebookBook.DEFAULT_GROUP_ID, now)
                .withCards(List.of(ref)));
        noteStore.writeBlockMeta(block, noteStore.cardPath(ref));
        refreshCachedView(noteIdOf(block.id()));
    }

    private NoteStore.CardData noteStoreRead(Path file) {
        return noteStore.readCard(new CardRef("", noteStore.paths().relative(file)));
    }

    /** 标题兜底：正文首个标题 → 文件名（去掉 .md 与尾部随机串）→ "无标题笔记"。 */
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




