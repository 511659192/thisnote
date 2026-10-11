package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.Card;
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
            super("每篇笔记最多 " + limit + " 张卡片，无法继续新增。请新建一篇笔记。", limit);
        }
    }

    public Dtos.CardsDto page(String noteId,
                              List<String> ids) {
        NoteMeta note = noteService.require(noteId);
        Set<String> wanted = ids == null ? null : new HashSet<>(ids);
        List<Card.CardMeta> rows = cardRepository.cards(noteId);
        if (wanted != null) {
            rows = rows.stream().filter(card -> wanted.contains(card.getId())).toList();
        }
        List<Dtos.CardView> views = new ArrayList<>(rows.size());
        for (Card.CardMeta card : rows) {
            views.add(new Dtos.CardView(card, noteRepository.content(card.getId()), note.getTitle()));
        }
        return new Dtos.CardsDto(note.getId(), note.getTitle(), views, maxCardsPerNote(), note.getUpdatedAt());
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
        String id = cardRepository.createAt(noteId,
                                            x,
                                            y,
                                            req == null ? null : req.w(),
                                            req == null ? null : req.h(),
                                            req == null ? null : req.color(),
                                            null,
                                            content,
                                            title);
        syncNoteFromCards(noteId);
        NoteMeta fresh = noteService.require(noteId);
        Card.CardMeta stored = cardRepository.find(id);
        return new Dtos.CardView(stored, content, fresh.getTitle());
    }

    public Dtos.CardView writeContent(String cardId,
                                      String contentMd,
                                      String baseContentMd) {
        requireCard(cardId);
        String noteId = noteRepository.noteIdOf(cardId);
        cardRepository.setContent(cardId, contentMd, baseContentMd);
        NoteMeta note = noteService.require(noteId);
        syncNoteFromCards(note.getId());
        NoteMeta fresh = noteService.require(note.getId());
        return new Dtos.CardView(cardRepository.find(cardId), contentMd == null ? "" : contentMd, fresh.getTitle());
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
            noteService.update(noteId, new Dtos.UpdateNodeRequest(null, next, null, null, null, null));
        }
    }

    private Card.CardMeta requireCard(String id) {
        Card.CardMeta card = cardRepository.find(id);
        if (card == null) {
            throw new NoteService.NotFoundException("card " + id);
        }
        return card;
    }

    public Card.CardMeta update(String cardId,
                                Dtos.CardUpdate changes) {
        requireCard(cardId);
        cardRepository.update(cardId,
                              changes.title(),
                              changes.x(),
                              changes.y(),
                              changes.w(),
                              changes.h(),
                              changes.color(),
                              changes.status(),
                              changes.collapsed());
        return cardRepository.find(cardId);
    }

    public void batchUpdate(Dtos.BatchCardUpdateRequest req) {
        List<Dtos.CardUpdate> updates = req == null || req.cards() == null ? List.of() : req.cards();
        cardRepository.updateBatch(updates);
    }

}
