package com.thisnode.ext.notes.service;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class CardService {

    private final CardRepository cardRepository;
    private final NoteRepository noteRepository;
    private final NoteService noteService;
    private final NotesConfig config;

    public static class CardLimitException extends com.thisnode.plugin.api.CardLimitException {
        public CardLimitException(int limit) {
            super("每篇笔记最多 " + limit + " 张卡片，无法继续新增。请新建一篇笔记。", limit);
        }
    }

    private record StoredCard(NoteMeta note, String fileName, Card card) {
        CardMeta meta() {
            return card.getMeta();
        }
    }

    private List<String> cardFileNames(NoteMeta note) {
        return note == null || note.getCardFileNames() == null ? List.of() : note.getCardFileNames();
    }

    private List<StoredCard> cards(NoteMeta note) {
        List<StoredCard> result = new ArrayList<>();
        for (String fileName : cardFileNames(note)) {
            Card card = cardRepository.get(fileName);
            if (card != null && card.getMeta() != null) {
                result.add(new StoredCard(note, fileName, card));
            }
        }
        return result;
    }

    private StoredCard locate(String cardId) {
        if (cardId == null || cardId.isBlank()) {
            return null;
        }
        for (NoteMeta note : noteRepository.getAll()) {
            for (String fileName : cardFileNames(note)) {
                Card card = cardRepository.get(fileName);
                if (card != null && card.getMeta() != null && cardId.equals(card.getMeta().getId())) {
                    return new StoredCard(note, fileName, card);
                }
            }
        }
        return null;
    }

    private StoredCard requireCard(String cardId) {
        StoredCard card = locate(cardId);
        if (card == null) {
            throw new NoteService.NotFoundException("card " + cardId);
        }
        return card;
    }

    private int count(NoteMeta note) {
        return cardFileNames(note).size();
    }

    private void requireCapacity(NoteMeta note, int adding) {
        int limit = config.getMaxCardsPerNote();
        if (count(note) + adding > limit) {
            throw new CardLimitException(limit);
        }
    }

    public Dtos.CardsDto page(String noteId, List<String> ids) {
        NoteMeta note = noteService.require(noteId);
        Set<String> wanted = ids == null ? null : new HashSet<>(ids);
        List<StoredCard> rows = cards(note);
        if (wanted != null) {
            rows = rows.stream().filter(card -> wanted.contains(card.meta().getId())).toList();
        }
        List<Dtos.CardView> views = new ArrayList<>(rows.size());
        for (StoredCard stored : rows) {
            views.add(new Dtos.CardView(stored.meta(), stored.card().getContent(), note.getTitle()));
        }
        return new Dtos.CardsDto(note.getId(), note.getTitle(), views, config.getMaxCardsPerNote(), note.getUpdatedAt());
    }

    public Dtos.CardView create(String noteId, Dtos.CreateCardRequest req) {
        NoteMeta note = noteService.require(noteId);
        requireCapacity(note, 1);
        String content = req == null || req.contentMd() == null ? "" : req.contentMd();
        String title = req == null || req.title() == null ? "" : req.title().trim();
        double[] flow = nextFlowPosition(note);
        double x = req != null && req.x() != null ? Math.max(0, req.x()) : flow[0];
        double y = req != null && req.y() != null ? Math.max(0, req.y()) : flow[1];
        double w = req == null || req.w() == null ? CardRepository.DEFAULT_W : Math.max(CardRepository.MIN_W, req.w());
        double h = req == null || req.h() == null ? CardRepository.DEFAULT_H : Math.max(CardRepository.MIN_H, req.h());
        List<StoredCard> existing = cards(note);
        int slot = insertionSlot(existing, y, h);
        long now = System.currentTimeMillis();
        CardMeta card = new CardMeta(CardMeta.SCHEMA, IdUtil.randomUUID(), title, x, y, w, h,
                slot, req == null ? null : req.color(), null, false, Map.of(), now, now);
        String fileName = cardRepository.save(note.getTitle(), card, content, null);
        List<String> fileNames = new ArrayList<>(cardFileNames(note));
        fileNames.add(Math.min(slot, fileNames.size()), fileName);
        note.setCardFileNames(fileNames);
        note.setUpdatedAt(now);
        noteRepository.save(note);
        return new Dtos.CardView(card, content, note.getTitle());
    }

    private double[] nextFlowPosition(NoteMeta note) {
        double bottom = cards(note).stream()
                .mapToDouble(stored -> stored.meta().getY() + stored.meta().getH())
                .max()
                .orElse(0);
        return new double[]{CardRepository.FLOW_X, bottom <= 0 ? 0 : bottom + CardRepository.FLOW_GAP};
    }

    private static int insertionSlot(List<StoredCard> cards, double y, double height) {
        double center = y + height / 2;
        long before = cards.stream()
                .map(StoredCard::meta)
                .filter(card -> card.getY() + card.getH() / 2 < center)
                .count();
        return (int) Math.min(before, cards.size());
    }

    public Dtos.CardView writeContent(String cardId, String contentMd, String baseContentMd) {
        StoredCard stored = requireCard(cardId);
        String current = stored.card().getContent();
        if (baseContentMd != null && !baseContentMd.equals(current)) {
            throw new com.thisnode.plugin.api.ConflictException(
                    "这张卡片已被外部修改（磁盘上的内容与你打开时的不一样），已停止保存");
        }
        CardMeta meta = stored.meta().setUpdatedAt(System.currentTimeMillis());
        String body = contentMd == null ? "" : contentMd;
        cardRepository.save(stored.note().getTitle(), meta, body, stored.fileName());
        stored.note().setUpdatedAt(System.currentTimeMillis());
        noteRepository.save(stored.note());
        return new Dtos.CardView(meta, body, stored.note().getTitle());
    }

    public CardMeta update(String cardId, Dtos.CardUpdate changes) {
        StoredCard stored = requireCard(cardId);
        CardMeta meta = stored.meta();
        double x = changes.x() == null ? meta.getX() : changes.x();
        double y = changes.y() == null ? meta.getY() : changes.y();
        double w = changes.w() == null ? meta.getW() : Math.max(CardRepository.MIN_W, changes.w());
        double h = changes.h() == null ? meta.getH() : Math.max(CardRepository.MIN_H, changes.h());
        meta.setX(x).setY(y).setW(w).setH(h);
        if (changes.title() != null) meta.setTitle(changes.title());
        if (changes.color() != null) meta.setColor(changes.color());
        if (changes.status() != null) meta.setStatus(changes.status());
        if (changes.collapsed() != null) meta.setCollapsed(changes.collapsed());
        meta.setUpdatedAt(System.currentTimeMillis());
        cardRepository.save(stored.fileName(), meta);
        return meta;
    }

    public void batchUpdate(Dtos.BatchCardUpdateRequest req) {
        List<Dtos.CardUpdate> updates = req == null || req.cards() == null ? List.of() : req.cards();
        for (Dtos.CardUpdate update : updates) {
            if (update != null && update.id() != null) {
                update(update.id(), update);
            }
        }
    }
}
