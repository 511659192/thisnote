package com.thisnode.ext.notes.repository;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.JsonFileUtils;
import com.thisnode.plugin.api.ConflictException;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 卡片：一篇笔记的页面由它们组成。
 *
 * <p>每张卡在磁盘上就是一个 md（正文）。这里做卡片的读写与装配：把 {@link Card.CardMeta} 与正文
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
    public static final int FLOW_GAP = 16;

    /**
     * Left edge of the page column.
     */
    public static final int FLOW_X = 0;

    private final NoteRepository noteRepository;

    public CardRepository(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    private Dtos.CardDto dto(Card.CardMeta card) {
        // 归属不在卡片里（改由笔记持清单），所以经索引反查 —— 一次内存 map 查询，不读文件。
        return new Dtos.CardDto(card.getId(),
                                noteRepository.noteIdOf(card.getId()),
                                card.getTitle(),
                                card.getX(),
                                card.getY(),
                                card.getW(),
                                card.getH(),
                                card.getZ(),
                                card.getColor(),
                                card.getStatus(),
                                card.isCollapsed(),
                                noteRepository.content(card.getId()),
                                card.getCreatedAt(),
                                card.getUpdatedAt());
    }

    public List<Dtos.CardDto> cards(String noteId) {
        List<Dtos.CardDto> out = new ArrayList<>();
        for (Card.CardMeta card : noteRepository.cards(noteId)) {
            out.add(dto(card));
        }
        return out;
    }

    public Dtos.CardDto find(String id) {
        Card.CardMeta card = noteRepository.card(id);
        return card == null ? null : dto(card);
    }

    public int count(String noteId) {
        return noteRepository.cardCount(noteId);
    }

    public String create(String noteId,
                         Double x,
                         Double y,
                         Double w,
                         Double h,
                         String color,
                         String status,
                         String contentMd,
                         String title) {
        return create(noteId, x, y, w, h, color, status, contentMd, title, null);
    }


    public String createAt(String noteId,
                           double x,
                           double y,
                           Double w,
                           Double h,
                           String color,
                           String status,
                           String contentMd,
                           String title) {
        double cardH = h == null ? DEFAULT_H : Math.max(MIN_H, h);
        double centre = y + cardH / 2;
        List<Card.CardMeta> existing = noteRepository.cards(noteId);
        int after = 0;
        for (Card.CardMeta card : existing) {
            if (card.getY() + card.getH() / 2 < centre) {
                after++;
            }
        }
        int slot = Math.min(after, existing.size());
        // 插进清单第 slot 位即可，后面的卡片由清单顺延 —— 不需要（也不应该）去动别人的位置。
        return create(noteId, x, y, w, h, color, status, contentMd, title, slot);
    }

    private String create(String noteId,
                          Double x,
                          Double y,
                          Double w,
                          Double h,
                          String color,
                          String status,
                          String contentMd,
                          String title,
                          Integer slot) {
        long now = System.currentTimeMillis();
        String id = IdUtil.randomUUID();
        // 几何默认值住在 CardMeta 里，所以手写的注释与这里写出来的会被同一套规则归一化。
        Card.CardMeta card = new Card.CardMeta(Card.CardMeta.SCHEMA,
                                               id,
                                               title == null ? "" : title,
                                               x == null ? 0 : Math.max(0, x),
                                               y == null ? 0 : Math.max(0, y),
                                               w == null ? DEFAULT_W : Math.max(MIN_W, w),
                                               h == null ? DEFAULT_H : Math.max(MIN_H, h),
                                               slot == null ? -1 : slot,
                                               color,
                                               status,
                                               false,
                                               Map.of(),
                                               now,
                                               now);
        putCard(noteId, card, contentMd == null ? "" : contentMd, slot == null ? -1 : slot);
        return id;
    }

    public void setContent(String cardId,
                           String contentMd,
                           String baseContentMd) {
        rejectIfChangedOnDisk(cardId, baseContentMd);
        setContent(cardId, contentMd);
    }

    public void setContent(String cardId,
                           String contentMd) {
        String noteId = noteRepository.noteIdOf(cardId);
        Card.CardMeta current = noteRepository.card(cardId);
        if (noteId != null && current != null) {
            putCard(noteId, current.touched(System.currentTimeMillis()), contentMd);
        }
    }

    /**
     * 磁盘上的正文与客户端依据的那份不一致就拒写；文件已被外部删掉同样拒写。
     */
    private void rejectIfChangedOnDisk(String cardId,
                                       String baseContentMd) {
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

    /**
     * All card contents for a note, in z order — the note's markdown is their join.
     */
    public List<String> contentsInOrder(String noteId) {
        List<String> out = new ArrayList<>();
        for (Card.CardMeta card : noteRepository.cards(noteId)) {
            out.add(noteRepository.content(card.getId()));
        }
        return out;
    }

    /**
     * Where a card goes when the caller supplies no point at all.
     *
     * <p>Only a fallback: the importer and the demo seeder use it for cardRefs that have no clicked
     * position. Cards that do have a point are placed there verbatim.
     */
    public double[] nextFlowPosition(String noteId) {
        double bottom = 0;
        for (Card.CardMeta card : noteRepository.cards(noteId)) {
            bottom = Math.max(bottom, card.getY() + card.getH());
        }
        return new double[]{FLOW_X, bottom <= 0 ? 0 : bottom + FLOW_GAP};
    }


    public void update(String id,
                       String title,
                       Double x,
                       Double y,
                       Double w,
                       Double h,
                       Integer z,
                       String color,
                       String status,
                       Boolean collapsed) {
        Card.CardMeta current = noteRepository.card(id);
        if (current == null) {
            return;
        }
        putCardMeta(current.withGeometry(x, y, w, h, z)
                           .withTitle(title != null ? title : current.getTitle())
                           .withAppearance(color, status, collapsed)
                           .touched(System.currentTimeMillis()));
    }

    /**
     * Batched geometry write for drag / resize / distribute.
     */
    public void updateBatch(List<Dtos.CardUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }
        for (Dtos.CardUpdate change : updates) {
            if (change == null || change.id() == null) {
                continue;
            }
            update(change.id(),
                   change.title(),
                   change.x(),
                   change.y(),
                   change.w(),
                   change.h(),
                   change.z(),
                   change.color(),
                   change.status(),
                   change.collapsed());
        }
    }

    /**
     * Highest z on the page; the next card goes on top.
     */
    public int nextZ(String noteId) {
        int top = 0;
        boolean any = false;
        for (Card.CardMeta card : noteRepository.cards(noteId)) {
            top = Math.max(top, card.getZ());
            any = true;
        }
        return any ? top + 1 : 1;
    }

    public int lowestZ(String noteId) {
        int low = 0;
        boolean any = false;
        for (Card.CardMeta card : noteRepository.cards(noteId)) {
            low = any ? Math.min(low, card.getZ()) : card.getZ();
            any = true;
        }
        return any ? low - 1 : -1;
    }

    public void delete(String id) {
        removeCard(id);
    }

    // ================================================================ 卡片写入

    /**
     * 写一张卡片（追加到清单末尾）并刷新该笔记的列表页缓存。
     */
    public Card.CardMeta putCard(String noteId,
                                 Card.CardMeta card,
                                 String contentMd) {
        return putCard(noteId, card, contentMd, -1);
    }

    /**
     * 写一张卡片，插进清单第 {@code slot} 位（{@code slot < 0} 表示追加）。
     */
    public Card.CardMeta putCard(String noteId,
                                 Card.CardMeta card,
                                 String contentMd,
                                 int slot) {
        NoteMeta note = noteRepository.getNoteMeta(noteId);
        if (note == null) {
            return null;
        }
        int at = indexOfCard(note, card.getId());
        Path knownFile = at >= 0 ? Path.of(noteRepository.getRoot().toString(), "cards", note.getCardPaths().get(at)) : null;
        Path file = noteRepository.writeCard(note.getTitle(), card, contentMd, knownFile);
        List<String> paths = new ArrayList<>(note.getCardPaths());
        String cardFileName = file.getFileName().toString();
        if (at >= 0) {
            paths.set(at, cardFileName);
        } else if (slot >= 0 && slot < paths.size()) {
            paths.add(slot, cardFileName);
        } else {
            paths.add(cardFileName);
        }
        note.setCardPaths(paths);
        note.setUpdatedAt(System.currentTimeMillis());
        JsonFileUtils.write(Path.of(noteRepository.getRoot().toString(), "meta", note.getId() + ".json"), note);
        return card;
    }

    public void putCardMeta(Card.CardMeta card) {
        if (card == null) {
            return;
        }
        NoteRepository.Owned owned = noteRepository.ownerOf(card.getId());
        if (owned == null) {
            return;
        }
        noteRepository.writeCardMeta(card, owned.file());
    }

    /**
     * 删一张卡片：先摘出笔记清单，再删文件。
     */
    public void removeCard(String cardId) {
        NoteRepository.Owned owned = noteRepository.ownerOf(cardId);
        if (owned == null) {
            return;
        }

        FileUtil.del(owned.file());
        List<String> paths = new ArrayList<>(owned.note().getCardPaths());
        paths.remove(owned.file().getFileName().toString());
        owned.note().setCardPaths(paths);
        owned.note().setUpdatedAt(System.currentTimeMillis());
        JsonFileUtils.write(Path.of(noteRepository.getRoot().toString(), "meta", owned.note().getId() + ".json"), owned.note());
    }


    private int indexOfCard(NoteMeta note,
                            String cardId) {
        for (int i = 0; i < note.getCardPaths().size(); i++) {
            Path file = Path.of(noteRepository.getRoot().toString(), "cards", note.getCardPaths().get(i));
            Card data = noteRepository.readCard(file);
            if (data != null && data.getMeta() != null && cardId.equals(data.getMeta().getId())) {
                return i;
            }
        }
        return -1;
    }
}
