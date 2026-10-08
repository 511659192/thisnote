package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.store.BlockMeta;
import com.thisnode.ext.notes.store.NoteCompositionService;
import com.thisnode.ext.notes.util.Ids;
import com.thisnode.plugin.api.ConflictException;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 卡片：一篇笔记的页面由它们组成。
 *
 * <p>每张卡在磁盘上就是一个 md（正文）+ 一个同名 sidecar（{@code noteId}、位置与尺寸）。
 * 所以"卡片"既是文件也是内存里的一条记录，{@link NoteCompositionService} 负责让两者一致，这里只做装配：
 * 把 {@link BlockMeta} 与正文拼成 API 的 DTO，把调用方的意图翻成索引上的写操作。
 *
 * <p>几何语义与数据库时代一致：卡片各就各位、允许重叠，任何写入都不会顺手移动别的卡片。
 */
@Repository
public class NoteBlockRepository {

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

    private final NoteCompositionService cards;

    public NoteBlockRepository(NoteCompositionService cards) {
        this.cards = cards;
    }

    private Dtos.NoteBlockDto dto(BlockMeta block) {
        // 归属不在卡片里（改由笔记持清单），所以经索引反查 —— 一次内存 map 查询，不读文件。
        return new Dtos.NoteBlockDto(block.id(), cards.noteIdOf(block.id()), block.title(),
                block.x(), block.y(), block.w(), block.h(), block.z(),
                block.color(), block.status(), block.collapsed(), cards.content(block.id()),
                block.createdAt(), block.updatedAt());
    }

    /**
     * Cards of a note in z order, exactly as they are stored. The order comes from the sidecar's
     * {@code z}; cards are never re-flowed, because that would move a card the user positioned.
     */
    public List<Dtos.NoteBlockDto> blocks(String noteId) {
        List<Dtos.NoteBlockDto> out = new ArrayList<>();
        for (BlockMeta block : cards.blocks(noteId)) {
            out.add(dto(block));
        }
        return out;
    }

    public Dtos.NoteBlockDto find(String id) {
        BlockMeta block = cards.block(id);
        return block == null ? null : dto(block);
    }

    public int count(String noteId) {
        return cards.blockCount(noteId);
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
        List<BlockMeta> existing = cards.blocks(noteId);
        int after = 0;
        for (BlockMeta block : existing) {
            if (block.y() + block.h() / 2 < centre) {
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
        // 几何默认值住在 BlockMeta 里，所以手写的注释与这里写出来的会被同一套规则归一化。
        BlockMeta block = new BlockMeta(BlockMeta.SCHEMA, id,
                title == null ? "" : title,
                x == null ? 0 : Math.max(0, x), y == null ? 0 : Math.max(0, y),
                w == null ? DEFAULT_W : Math.max(MIN_W, w),
                h == null ? DEFAULT_H : Math.max(MIN_H, h),
                slot == null ? -1 : slot, color, status, false, Map.of(), now, now);
        cards.putBlock(noteId, block, contentMd == null ? "" : contentMd,
                slot == null ? -1 : slot);
        return id;
    }

    /**
     * 保存卡片正文，并先做冲突保护：{@code baseContentMd} 是客户端编辑时所依据的正文。
     *
     * <p>比对的是**磁盘**（{@link NoteCompositionService#contentOnDisk}）而不是索引缓存 —— 外部编辑之后、
     * 下一次对账之前缓存里还是旧内容。{@code null} 表示调用方不带基线（内部改写与旧客户端），
     * 保持原有行为。
     */
    public void setContent(String blockId, String contentMd, String baseContentMd) {
        rejectIfChangedOnDisk(blockId, baseContentMd);
        setContent(blockId, contentMd);
    }

    public void setContent(String blockId, String contentMd) {
        String noteId = cards.noteIdOf(blockId);
        BlockMeta current = cards.block(blockId);
        if (noteId != null && current != null) {
            cards.putBlock(noteId, current.touched(System.currentTimeMillis()), contentMd);
        }
    }

    /** 磁盘上的正文与客户端依据的那份不一致就拒写；文件已被外部删掉同样拒写。 */
    private void rejectIfChangedOnDisk(String blockId, String baseContentMd) {
        if (baseContentMd == null) {
            return;
        }
        String onDisk = cards.contentOnDisk(blockId);
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
        for (BlockMeta block : cards.blocks(noteId)) {
            out.add(cards.content(block.id()));
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
        for (BlockMeta block : cards.blocks(noteId)) {
            bottom = Math.max(bottom, block.y() + block.h());
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
                       Dtos.NoteBlockDto prefetched) {
        BlockMeta current = cards.block(id);
        if (current == null) {
            return;
        }
        cards.putBlockMeta(current
                .withGeometry(x, y, w, h, z)
                .withTitle(title != null ? title : current.title())
                .withAppearance(color, status, collapsed)
                .touched(System.currentTimeMillis()));
    }

    /** Batched geometry write for drag / resize / distribute. */
    public void updateBatch(List<Dtos.BlockPatch> patches) {
        if (patches == null || patches.isEmpty()) {
            return;
        }
        for (Dtos.BlockPatch patch : patches) {
            if (patch == null || patch.id() == null) {
                continue;
            }
            update(patch.id(), patch.title(), patch.x(), patch.y(), patch.w(), patch.h(),
                    patch.z(), patch.color(), patch.status(), patch.collapsed(), null);
        }
    }

    public List<Dtos.NoteBlockDto> findMany(List<String> ids) {
        List<Dtos.NoteBlockDto> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids) {
            BlockMeta block = cards.block(id);
            if (block != null) {
                out.add(dto(block));
            }
        }
        return out;
    }

    /** Highest z on the page; the next block goes on top. */
    public int nextZ(String noteId) {
        int top = 0;
        boolean any = false;
        for (BlockMeta block : cards.blocks(noteId)) {
            top = Math.max(top, block.z());
            any = true;
        }
        return any ? top + 1 : 1;
    }

    public int lowestZ(String noteId) {
        int low = 0;
        boolean any = false;
        for (BlockMeta block : cards.blocks(noteId)) {
            low = any ? Math.min(low, block.z()) : block.z();
            any = true;
        }
        return any ? low - 1 : -1;
    }

    public void delete(String id) {
        cards.removeBlock(id);
    }

    public void deleteMany(List<String> ids) {
        if (ids == null) {
            return;
        }
        for (String id : ids) {
            cards.removeBlock(id);
        }
    }

}
