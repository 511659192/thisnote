package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.store.CardFile;
import com.thisnode.ext.notes.store.CardMeta;
import com.thisnode.ext.notes.store.CardRef;
import com.thisnode.ext.notes.store.NoteFilePaths;
import com.thisnode.ext.notes.store.NoteMeta;
import com.thisnode.ext.notes.util.Ids;
import com.thisnode.plugin.api.ConflictException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 卡片：一篇笔记的页面由它们组成。
 *
 * <p>每张卡在磁盘上就是一个 md（正文）。这里做卡片的读写与装配：把 {@link CardMeta} 与正文
 * 拼成 API 的 DTO，把调用方的意图翻成 {@link NoteRepository} 上的文件读写。
 *
 * <p>几何语义与数据库时代一致：卡片各就各位、允许重叠，任何写入都不会顺手移动别的卡片。
 */
@Repository
public class CardRepository {

    /**
     * Card geometry. Cards are wide and roomy by default: a card holds a real piece of the
     * note and is written in place, so a 320px column was too cramped to write in.
     */
    public static final int DEFAULT_W = 720;
    public static final int DEFAULT_H = 260;
    public static final int MIN_W = 240;
    public static final int MIN_H = 88;
    /** Vertical gap between consecutive cards in the page flow. */
    public static final int FLOW_GAP = 16;

    /** Left edge of the page column. */
    public static final int FLOW_X = 0;

    private final NoteRepository noteRepository;
    private final NoteFilePaths noteFilePaths;

    public CardRepository(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
        this.noteFilePaths = noteRepository.paths();
    }

    private Dtos.CardDto dto(CardMeta card) {
        // 归属不在卡片里（改由笔记持清单），所以经索引反查 —— 一次内存 map 查询，不读文件。
        return new Dtos.CardDto(card.id(), noteRepository.noteIdOf(card.id()), card.title(),
                card.x(), card.y(), card.w(), card.h(), card.z(),
                card.color(), card.status(), card.collapsed(), noteRepository.content(card.id()),
                card.createdAt(), card.updatedAt());
    }

    /**
     * Cards of a note in z order, exactly as they are stored. The order comes from the sidecar's
     * {@code z}; cards are never re-flowed, because that would move a card the user positioned.
     */
    public List<Dtos.CardDto> cards(String noteId) {
        List<Dtos.CardDto> out = new ArrayList<>();
        for (CardMeta card : noteRepository.cards(noteId)) {
            out.add(dto(card));
        }
        return out;
    }

    public Dtos.CardDto find(String id) {
        CardMeta card = noteRepository.card(id);
        return card == null ? null : dto(card);
    }

    public int count(String noteId) {
        return noteRepository.cardCount(noteId);
    }

    public String create(String noteId, Double x, Double y, Double w, Double h,
                         String color, String status, String contentMd) {
        return create(noteId, x, y, w, h, color, status, contentMd, null);
    }

    public String create(String noteId, Double x, Double y, Double w, Double h,
                         String color, String status, String contentMd, String title) {
        return create(noteId, x, y, w, h, color, status, contentMd, title, null);
    }

    /**
     * Place a new card at the caller's point, in the page order that point implies.
     *
     * <p>z is the reading order of the note (the note's markdown is its cards joined in z order),
     * so a card dropped between two others must also sit between them in z. The slot is chosen by
     * vertical centre — the new card goes after every card whose centre is above its own — and the
     * cards below shift down one slot. y itself is kept verbatim: placing a card never moves a
     * neighbour.
     */
    public String createAt(String noteId, double x, double y, Double w, Double h,
                           String color, String status, String contentMd, String title) {
        double cardH = h == null ? DEFAULT_H : Math.max(MIN_H, h);
        double centre = y + cardH / 2;
        List<CardMeta> existing = noteRepository.cards(noteId);
        int after = 0;
        for (CardMeta card : existing) {
            if (card.y() + card.h() / 2 < centre) {
                after++;
            }
        }
        int slot = Math.min(after, existing.size());
        // 插进清单第 slot 位即可，后面的卡片由清单顺延 —— 不需要（也不应该）去动别人的位置。
        return create(noteId, x, y, w, h, color, status, contentMd, title, slot);
    }

    private String create(String noteId, Double x, Double y, Double w, Double h,
                          String color, String status, String contentMd, String title,
                          Integer slot) {
        long now = System.currentTimeMillis();
        String id = Ids.newId();
        // 几何默认值住在 CardMeta 里，所以手写的注释与这里写出来的会被同一套规则归一化。
        CardMeta card = new CardMeta(CardMeta.SCHEMA, id,
                title == null ? "" : title,
                x == null ? 0 : Math.max(0, x), y == null ? 0 : Math.max(0, y),
                w == null ? DEFAULT_W : Math.max(MIN_W, w),
                h == null ? DEFAULT_H : Math.max(MIN_H, h),
                slot == null ? -1 : slot, color, status, false, Map.of(), now, now);
        putCard(noteId, card, contentMd == null ? "" : contentMd,
                slot == null ? -1 : slot);
        return id;
    }

    /**
     * 保存卡片正文，并先做冲突保护：{@code baseContentMd} 是客户端编辑时所依据的正文。
     *
     * <p>比对的是**磁盘**（{@link #contentOnDisk(String)}）而不是索引缓存 —— 外部编辑之后、
     * 下一次对账之前缓存里还是旧内容。{@code null} 表示调用方不带基线（内部改写与旧客户端），
     * 保持原有行为。
     */
    public void setContent(String cardId, String contentMd, String baseContentMd) {
        rejectIfChangedOnDisk(cardId, baseContentMd);
        setContent(cardId, contentMd);
    }

    public void setContent(String cardId, String contentMd) {
        String noteId = noteRepository.noteIdOf(cardId);
        CardMeta current = noteRepository.card(cardId);
        if (noteId != null && current != null) {
            putCard(noteId, current.touched(System.currentTimeMillis()), contentMd);
        }
    }

    /** 磁盘上的正文与客户端依据的那份不一致就拒写；文件已被外部删掉同样拒写。 */
    private void rejectIfChangedOnDisk(String cardId, String baseContentMd) {
        if (baseContentMd == null) {
            return;
        }
        String onDisk = noteRepository.contentOnDisk(cardId);
        if (onDisk == null) {
            throw new ConflictException("这张卡片的文件已不在磁盘上（可能被外部删除），已停止保存");
        }
        if (!baseContentMd.equals(onDisk)) {
            throw new ConflictException("这张卡片已被外部修改（磁盘上的内容与你打开时的不一样），已停止保存");
        }
    }

    /** All card contents for a note, in z order — the note's markdown is their join. */
    public List<String> contentsInOrder(String noteId) {
        List<String> out = new ArrayList<>();
        for (CardMeta card : noteRepository.cards(noteId)) {
            out.add(noteRepository.content(card.id()));
        }
        return out;
    }

    /**
     * Where a card goes when the caller supplies no point at all.
     *
     * <p>Only a fallback: the importer and the demo seeder use it for cards that have no clicked
     * position. Cards that do have a point are placed there verbatim.
     */
    public double[] nextFlowPosition(String noteId) {
        double bottom = 0;
        for (CardMeta card : noteRepository.cards(noteId)) {
            bottom = Math.max(bottom, card.y() + card.h());
        }
        return new double[]{FLOW_X, bottom <= 0 ? 0 : bottom + FLOW_GAP};
    }

    /**
     * Apply a geometry/appearance patch, leaving out any field the caller did not supply.
     *
     * <p>{@code prefetched} exists for signature compatibility with the database version, where it
     * saved a read per card in batched moves; the index already holds every card, so it is unused.
     */
    public void update(String id, Double x, Double y, Double w, Double h, Integer z,
                       String color, String status, Boolean collapsed) {
        update(id, null, x, y, w, h, z, color, status, collapsed, null);
    }

    public void update(String id, String title, Double x, Double y, Double w, Double h, Integer z,
                       String color, String status, Boolean collapsed) {
        update(id, title, x, y, w, h, z, color, status, collapsed, null);
    }

    public void update(String id, String title, Double x, Double y, Double w, Double h, Integer z,
                       String color, String status, Boolean collapsed,
                       Dtos.CardDto prefetched) {
        CardMeta current = noteRepository.card(id);
        if (current == null) {
            return;
        }
        putCardMeta(current
                .withGeometry(x, y, w, h, z)
                .withTitle(title != null ? title : current.title())
                .withAppearance(color, status, collapsed)
                .touched(System.currentTimeMillis()));
    }

    /** Batched geometry write for drag / resize / distribute. */
    public void updateBatch(List<Dtos.CardPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        for (Dtos.CardPatch patch : patches) {
            if (patch == null || patch.id() == null) {
                continue;
            }
            update(patch.id(), patch.title(), patch.x(), patch.y(), patch.w(), patch.h(),
                    patch.z(), patch.color(), patch.status(), patch.collapsed(), null);
        }
    }

    public List<Dtos.CardDto> findMany(List<String> ids) {
        List<Dtos.CardDto> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            CardMeta card = noteRepository.card(id);
            if (card != null) {
                out.add(dto(card));
            }
        }
        return out;
    }

    /** Highest z on the page; the next card goes on top. */
    public int nextZ(String noteId) {
        int top = 0;
        boolean any = false;
        for (CardMeta card : noteRepository.cards(noteId)) {
            top = Math.max(top, card.z());
            any = true;
        }
        return any ? top + 1 : 1;
    }

    public int lowestZ(String noteId) {
        int low = 0;
        boolean any = false;
        for (CardMeta card : noteRepository.cards(noteId)) {
            low = any ? Math.min(low, card.z()) : card.z();
            any = true;
        }
        return any ? low - 1 : -1;
    }

    public void delete(String id) {
        removeCard(id);
    }

    public void deleteMany(List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            removeCard(id);
        }
    }

    // ================================================================ 卡片写入

    /** 写一张卡片（追加到清单末尾）并刷新该笔记的列表页缓存。 */
    public CardMeta putCard(String noteId, CardMeta card, String contentMd) {
        return putCard(noteId, card, contentMd, -1);
    }

    /** 写一张卡片，插进清单第 {@code slot} 位（{@code slot < 0} 表示追加）。 */
    public CardMeta putCard(String noteId, CardMeta card, String contentMd, int slot) {
        NoteMeta note = noteRepository.note(noteId);
        if (note == null) {
            return null;
        }
        CardRef existing = refOf(note, card.id());
        Path knownFile = existing == null ? null : noteRepository.cardPath(existing);
        Path file = knownFile != null ? knownFile
                : resolveContentPath(note.title(), NoteFilePaths.cardSlug(card.title(), contentMd),
                        card.id());
        noteRepository.writeText(file, CardFile.compose(card, contentMd));
        CardRef ref = CardRef.of(card.id(), noteFilePaths.relative(file));

        List<CardRef> cardRefs = new ArrayList<>(note.cards());
        int at = indexOf(cardRefs, card.id());
        if (at >= 0) {
            cardRefs.set(at, ref);
        } else if (slot >= 0 && slot < cardRefs.size()) {
            cardRefs.add(slot, ref);
        } else {
            cardRefs.add(ref);
        }
        noteRepository.writeNoteMeta(note.withCards(cardRefs).touched(System.currentTimeMillis()));
        noteRepository.refreshCachedView(noteId);
        return card;
    }

    /** 只改卡片元数据（重写头部注释，正文原样保留）。 */
    public void writeCardMeta(CardMeta card, CardRef ref) {
        Path contentFile = noteRepository.cardPath(ref);
        String current = noteRepository.readText(contentFile);
        if (current == null) {
            noteRepository.writeText(contentFile, CardFile.compose(card, ""));
            return;
        }
        noteRepository.writeText(contentFile, CardFile.compose(card, CardFile.bodyOf(current)));
    }

    /** 只改卡片元数据；与 {@link #writeCardMeta(CardMeta, CardRef)} 同义。 */
    public void putCardMeta(CardMeta card) {
        if (card == null) {
            return;
        }
        NoteRepository.Owned owned = noteRepository.ownerOf(card.id());
        if (owned == null) {
            return;
        }
        noteRepository.writeCardMeta(card, noteRepository.cardPath(owned.ref()));
    }

    /** 删一张卡片：先摘出笔记清单，再删文件，最后刷新这篇笔记的列表页缓存。 */
    public void removeCard(String cardId) {
        NoteRepository.Owned owned = noteRepository.ownerOf(cardId);
        if (owned == null) {
            return;
        }
        noteRepository.deleteCardFiles(noteRepository.cardPath(owned.ref()));
        List<CardRef> cardRefs = new ArrayList<>(owned.note().cards());
        cardRefs.removeIf(ref -> ref.id().equals(cardId));
        noteRepository.writeNoteMeta(owned.note().withCards(cardRefs).touched(System.currentTimeMillis()));
        noteRepository.refreshCachedView(owned.note().id());
    }

    /** 卡片文件在磁盘上的路径（找不到给 {@code null}）。 */
    public Path fileOf(String cardId) {
        NoteRepository.Owned owned = noteRepository.ownerOf(cardId);
        return owned == null ? null : noteRepository.cardPath(owned.ref());
    }

    /**
     * 决定一张新卡片落在哪个文件：{@code <slug>-<shortId>.md}。同名文件已存在时把短 id 逐段加长，
     * 实在不行用完整 id 兜底。
     */
    private Path resolveContentPath(String noteTitle, String cardSlug, String blockId) {
        String noteSlug = NoteFilePaths.cardSlug(noteTitle, null);
        String prefix = NoteFilePaths.cardFilePrefix(noteSlug, cardSlug);
        Path candidate = noteFilePaths.cardFilePath(noteSlug, cardSlug, blockId);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String hex = blockId.replace("-", "");
        for (int len = 12; len < hex.length(); len += 4) {
            Path longer = noteFilePaths.cardsDir().resolve(prefix + "-" + hex.substring(0, len)
                    + NoteFilePaths.MD_SUFFIX);
            if (!Files.exists(longer)) {
                return longer;
            }
        }
        return noteFilePaths.cardsDir().resolve(prefix + "-" + blockId + NoteFilePaths.MD_SUFFIX);
    }

    private static CardRef refOf(NoteMeta note, String cardId) {
        for (CardRef ref : note.cards()) {
            if (ref.id().equals(cardId)) {
                return ref;
            }
        }
        return null;
    }

    private static int indexOf(List<CardRef> cardRefs, String cardId) {
        for (int i = 0; i < cardRefs.size(); i++) {
            if (cardRefs.get(i).id().equals(cardId)) {
                return i;
            }
        }
        return -1;
    }
}
