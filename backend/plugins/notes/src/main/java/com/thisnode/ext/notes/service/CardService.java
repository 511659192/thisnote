package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.repository.TagRepository;
import com.thisnode.ext.notes.domain.NoteMeta;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A note's page: the cards it is made of.
 *
 * <p><b>Model.</b> A note *is* the page. Each card owns a fragment of the note's content
 * ({@code cards md content}) plus its position; the note's markdown is the join of its
 * cards in z order. So there is exactly one copy of the content, and a card's text never
 * shifts when a neighbouring card grows — which is what makes "write in the card" safe.
 *
 * <p>Card content is authoritative. The note's markdown is a derived projection, kept in sync
 * on every write so search, links, the Markdown mirror and export all keep working unchanged.
 */
@Service
@RequiredArgsConstructor
public class CardService {

    public static final String DEFAULT_COLOR = "#3b82f6";

    private final CardRepository cardRepository;
    private final NoteRepository noteRepository;
    private final NoteService noteService;
    private final TagRepository tagRepository;

    /** Cap on cards per note; keeps a page inside the interaction budget it is measured for. */
    @Value("${thisnode.max-cards-per-note:100}")
    private int maxCardsPerNote;

    public int maxCardsPerNote() {
        return Math.max(1, maxCardsPerNote);
    }

    /** Thrown when a page already holds the maximum number of cards. */
    public static class CardLimitException extends com.thisnode.plugin.api.CardLimitException {
        public CardLimitException(int limit) {
            super("每篇笔记最多 " + limit + " 张卡片。请新建一篇笔记，或先合并/删除部分卡片。", limit);
        }
    }

    // ---------------------------------------------------------------- read

    /**
     * 一篇笔记的卡片：{@code ids} 为空表示整页，给了就只回这几张。
     *
     * <p>这是「按需加载」的入口：列表页只带卡片清单（不读卡片文件），打开笔记后按视口分批
     * 用 {@code ids} 来取——几何与正文一起给，因为它们同在一次读取里（都得打开卡片文件）。
     *
     * <p>空页面不再在这里补建卡片：读路径不该写盘。不变量「笔记至少有一张卡」由
     * {@code NoteService.create} 在新建笔记时建立。
     */
    public Dtos.CardsDto page(String noteId, List<String> ids) {
        NoteMeta note = noteService.require(noteId);
        Set<String> wanted = ids == null ? null : new HashSet<>(ids);
        List<Dtos.CardDto> rows = cardRepository.cards(noteId);
        if (wanted != null) {
            rows = rows.stream().filter(b -> wanted.contains(b.id())).toList();
        }
        List<Dtos.TagDto> noteTags = tagRepository.tagsOf(noteId);
        List<Dtos.CardView> views = new ArrayList<>(rows.size());
        for (Dtos.CardDto b : rows) {
            views.add(new Dtos.CardView(b, b.contentMd(), note.title(), noteTags));
        }
        return new Dtos.CardsDto(note.id(), note.title(), views, noteTags,
                maxCardsPerNote(), note.updatedAt());
    }

    // ---------------------------------------------------------------- write

    private void requireCapacity(String noteId, int adding) {
        int limit = maxCardsPerNote();
        if (cardRepository.count(noteId) + adding > limit) {
            throw new CardLimitException(limit);
        }
    }

    /** Add an empty (or seeded) card to a note's page. */
    public Dtos.CardView create(String noteId, Dtos.CreateCardRequest req) {
        noteService.require(noteId);
        requireCapacity(noteId, 1);
        String content = req == null || req.contentMd() == null ? "" : req.contentMd();
        String title = req == null || req.title() == null ? "" : req.title().trim();
        // The caller's point is honoured: a double-click places the card where the user aimed.
        // Callers that have no point to give (the Markdown importer, the demo seeder's fallback)
        // still get appended below the column, so a card is never dropped on top of another.
        double[] flow = cardRepository.nextFlowPosition(noteId);
        double x = req != null && req.x() != null ? Math.max(0, req.x()) : flow[0];
        double y = req != null && req.y() != null ? Math.max(0, req.y()) : flow[1];
        String id = cardRepository.createAt(noteId, x, y,
                req == null ? null : req.w(), req == null ? null : req.h(),
                req == null ? null : req.color(), null, content, title);
        syncNoteFromCards(noteId);
        NoteMeta fresh = noteService.require(noteId);
        Dtos.CardDto stored = cardRepository.find(id);
        return new Dtos.CardView(stored, stored.contentMd(), fresh.title(),
                tagRepository.tagsOf(noteId));
    }

    /**
     * Write markdown typed inside a card. The card owns this text; the note follows.
     *
     * <p>{@code baseContentMd} 是客户端编辑时所依据的正文：磁盘上已经不是那一份就拒写（409），
     * 免得把外部编辑器改过的内容覆盖掉。
     */
    public Dtos.CardView writeContent(String cardId, String contentMd, String baseContentMd) {
        Dtos.CardDto card = requireCard(cardId);
        cardRepository.setContent(cardId, contentMd, baseContentMd);
        NoteMeta note = noteService.require(card.noteId());
        syncNoteFromCards(note.id());
        NoteMeta fresh = noteService.require(note.id());
        return new Dtos.CardView(cardRepository.find(cardId),
                contentMd == null ? "" : contentMd, fresh.title(), tagRepository.tagsOf(note.id()));
    }

    /**
     * Rebuild the note's markdown from its cards (z order), so search, links, the Markdown
     * mirror and JSON export all see the text that was typed into the cards.
     */
    private void syncNoteFromCards(String noteId) {
        List<String> parts = cardRepository.contentsInOrder(noteId);
        StringBuilder joined = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append("\n\n");
            }
            joined.append(part.strip());
        }
        String next = joined.toString();
        String current = noteRepository.noteMarkdown(noteId);
        if (!next.equals(current)) {
            noteService.update(noteId, new Dtos.UpdateNodeRequest(
                    null, next, null, null, null, null, null));
        }
    }

    private Dtos.CardDto requireCard(String id) {
        Dtos.CardDto card = cardRepository.find(id);
        if (card == null) {
            throw new NoteService.NotFoundException("card " + id);
        }
        return card;
    }

    public Dtos.CardDto update(String cardId, Dtos.CardUpdate changes) {
        requireCard(cardId);
        cardRepository.update(cardId, changes.title(), changes.x(), changes.y(), changes.w(), changes.h(),
                changes.z(), changes.color(), changes.status(), changes.collapsed());
        // Resizing a card leaves its neighbours exactly where they are: cards are positioned
        // independently, so there is no column to re-close.
        return cardRepository.find(cardId);
    }

    /** Batched geometry write for drag / resize / distribute. */
    public void batchUpdate(Dtos.BatchCardUpdateRequest req) {
        List<Dtos.CardUpdate> updates = req == null || req.cards() == null ? List.of() : req.cards();
        if (updates.isEmpty()) {
            return;
        }
        cardRepository.updateBatch(updates);
    }

    /**
     * Duplicate a card into a brand-new note (so the copy can diverge from the original).
     * The new note starts with the card's text.
     */
    public Dtos.CardDto duplicate(String cardId, double dx, double dy) {
        Dtos.CardDto src = requireCard(cardId);
        String newNoteId = noteService.create(new Dtos.CreateNodeRequest(
                null, src.contentMd() == null ? "" : src.contentMd(), "note",
                null, null));
        String id = cardRepository.create(newNoteId, 0d, 0d, src.w(), src.h(), src.color(), src.status(),
                src.contentMd() == null ? "" : src.contentMd(), src.title());
        return cardRepository.find(id);
    }

    /**
     * Delete a card. {@code deleteContent} also removes the note when the card was its only
     * content; otherwise the note's markdown is rebuilt from the remaining cards.
     */
    public void delete(String cardId, boolean deleteContent) {
        Dtos.CardDto card = requireCard(cardId);
        NoteMeta note = noteRepository.getOne(card.noteId());
        if (cardRepository.count(card.noteId()) <= 1) {
            if (deleteContent) {
                noteService.delete(card.noteId());
                return;
            }
            // Keep the page non-empty: clear the last card rather than leaving nothing.
            cardRepository.setContent(cardId, "");
            if (note != null) {
                syncNoteFromCards(note.id());
            }
            return;
        }
        cardRepository.delete(cardId);
        if (note != null) {
            // The gap the card leaves behind stays open: the remaining cards keep the positions
            // the user gave them.
            syncNoteFromCards(note.id());
        }
    }

    /** z-order: bring a card to the front or send it back. */
    public Dtos.CardDto zOrder(String cardId, String op) {
        Dtos.CardDto card = requireCard(cardId);
        Integer z = switch (op == null ? "" : op) {
            case "top" -> cardRepository.nextZ(card.noteId());
            case "bottom" -> cardRepository.lowestZ(card.noteId());
            case "up" -> card.z() + 1;
            case "down" -> card.z() - 1;
            default -> card.z();
        };
        cardRepository.update(cardId, null, null, null, null, null, z, null, null, null);
        return cardRepository.find(cardId);
    }

}
