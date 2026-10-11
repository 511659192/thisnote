package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Service
@RequiredArgsConstructor
public class CardService {
    private final CardRepository cardRepository;
    private final NoteRepository noteRepository;
    private final NoteService noteService;

    @Value("${thisnode.max-cards-per-note:100}")
    private int maxCardsPerNote;

    public int maxCardsPerNote() {
        return Math.max(1, maxCardsPerNote);
    }

    public static class CardLimitException
            extends com.thisnode.plugin.api.CardLimitException {
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
    public Dtos.CardsDto page(String noteId,
                              List<String> ids) {
        NoteMeta note = noteService.require(noteId);
        Set<String> wanted = ids == null ? null : new HashSet<>(ids);
        List<Dtos.CardDto> rows = cardRepository.cards(noteId);
        if (wanted != null) {
            rows = rows.stream().filter(b -> wanted.contains(b.id())).toList();
        }
        List<Dtos.CardView> views = new ArrayList<>(rows.size());
        for (Dtos.CardDto b : rows) {
            views.add(new Dtos.CardView(b, b.contentMd(), note.getTitle()));
        }
        return new Dtos.CardsDto(note.getId(), note.getTitle(), views,
                                 maxCardsPerNote(), note.getUpdatedAt());
    }

    // ---------------------------------------------------------------- write

    private void requireCapacity(String noteId,
                                 int adding) {
        int limit = maxCardsPerNote();
        if (cardRepository.count(noteId) + adding > limit) {
            throw new CardLimitException(limit);
        }
    }

    /**
     * Add an empty (or seeded) card to a note's page.
     */
    public Dtos.CardView create(String noteId,
                                Dtos.CreateCardRequest req) {
        noteService.require(noteId);
        requireCapacity(noteId, 1);
        String content = req == null || req.contentMd() == null ? "" : req.contentMd();
        String title = req == null || req.title() == null ? "" : req.title().trim();
        double[] flow = cardRepository.nextFlowPosition(noteId);
        double x = req != null && req.x() != null ? Math.max(0, req.x()) : flow[0];
        double y = req != null && req.y() != null ? Math.max(0, req.y()) : flow[1];
        String id = cardRepository.createAt(noteId, x, y,
                                            req == null ? null : req.w(), req == null ? null : req.h(),
                                            req == null ? null : req.color(), null, content, title);
        syncNoteFromCards(noteId);
        NoteMeta fresh = noteService.require(noteId);
        Dtos.CardDto stored = cardRepository.find(id);
        return new Dtos.CardView(stored, stored.contentMd(), fresh.getTitle());
    }

    public Dtos.CardView writeContent(String cardId,
                                      String contentMd,
                                      String baseContentMd) {
        Dtos.CardDto card = requireCard(cardId);
        cardRepository.setContent(cardId, contentMd, baseContentMd);
        NoteMeta note = noteService.require(card.noteId());
        syncNoteFromCards(note.getId());
        NoteMeta fresh = noteService.require(note.getId());
        return new Dtos.CardView(cardRepository.find(cardId),
                                 contentMd == null ? "" : contentMd, fresh.getTitle());
    }

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
        String current = noteRepository.text(noteId);
        if (!next.equals(current)) {
            noteService.update(noteId, new Dtos.UpdateNodeRequest(
                    null, next, null, null, null, null));
        }
    }

    private Dtos.CardDto requireCard(String id) {
        Dtos.CardDto card = cardRepository.find(id);
        if (card == null) {
            throw new NoteService.NotFoundException("card " + id);
        }
        return card;
    }

    public Dtos.CardDto update(String cardId,
                               Dtos.CardUpdate changes) {
        requireCard(cardId);
        cardRepository.update(cardId, changes.title(), changes.x(), changes.y(), changes.w(), changes.h(),
                              changes.z(), changes.color(), changes.status(), changes.collapsed());
        return cardRepository.find(cardId);
    }

    /**
     * Batched geometry write for drag / resize / distribute.
     */
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
    public Dtos.CardDto duplicate(String cardId,
                                  double dx,
                                  double dy) {
        Dtos.CardDto src = requireCard(cardId);
        String newNoteId = noteService.create(new Dtos.CreateNodeRequest(
                null, src.contentMd() == null ? "" : src.contentMd(), "note", null));
        String id = cardRepository.create(newNoteId, 0d, 0d, src.w(), src.h(), src.color(), src.status(),
                                          src.contentMd() == null ? "" : src.contentMd(), src.title());
        return cardRepository.find(id);
    }

    public void delete(String cardId,
                       boolean deleteContent) {
        Dtos.CardDto card = requireCard(cardId);
        NoteMeta note = noteRepository.getNoteMeta(card.noteId());
        if (cardRepository.count(card.noteId()) <= 1) {
            if (deleteContent) {
                noteService.delete(card.noteId());
                return;
            }
            // Keep the page non-empty: clear the last card rather than leaving nothing.
            cardRepository.setContent(cardId, "");
            if (note != null) {
                syncNoteFromCards(note.getId());
            }
            return;
        }
        cardRepository.delete(cardId);
        if (note != null) {
            syncNoteFromCards(note.getId());
        }
    }

    /**
     * z-order: bring a card to the front or send it back.
     */
    public Dtos.CardDto zOrder(String cardId,
                               String op) {
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
