package com.thisnode.ext.notes.service;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.api.dto.BatchCardUpdateRequest;
import com.thisnode.ext.notes.api.dto.CardUpdate;
import com.thisnode.ext.notes.api.dto.CardView;
import com.thisnode.ext.notes.api.dto.CardsDto;
import com.thisnode.ext.notes.api.dto.CreateCardRequest;
import com.thisnode.ext.notes.api.dto.WriteContentRequest;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.exception.CardLimitException;
import com.thisnode.ext.notes.exception.NotFoundException;
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
            throw new NotFoundException("card " + cardId);
        }
        return card;
    }

    private int count(NoteMeta note) {
        return cardFileNames(note).size();
    }

    private void requireCapacity(NoteMeta note,
                                 int adding) {
        int limit = config.getMaxCardsPerNote();
        if (count(note) + adding > limit) {
            throw new CardLimitException(limit);
        }
    }

    public CardsDto page(String noteId,
                         List<String> ids) {
        NoteMeta note = noteService.require(noteId);
        Set<String> wanted = ids == null ? null : new HashSet<>(ids);
        List<StoredCard> rows = cards(note);
        if (wanted != null) {
            rows = rows.stream().filter(card -> wanted.contains(card.meta().getId())).toList();
        }
        List<CardView> views = new ArrayList<>(rows.size());
        for (StoredCard stored : rows) {
            views.add(new CardView(stored.meta(), stored.card().getContent(), note.getTitle()));
        }
        return new CardsDto(note.getId(), note.getTitle(), views, config.getMaxCardsPerNote(), note.getUpdatedAt());
    }

    public CardView create(String noteId,
                           CreateCardRequest req) {
        NoteMeta note = noteService.require(noteId);
        requireCapacity(note, 1);
        String content = req.contentMd();
        String title = req.title();
        double[] flow = nextFlowPosition(note);
        double x = req.x() == null ? flow[0] : Math.max(0, req.x());
        double y = req.y() == null ? flow[1] : Math.max(0, req.y());
        double w = Math.max(CardRepository.MIN_W, req.w());
        double h = Math.max(CardRepository.MIN_H, req.h());
        List<StoredCard> existing = cards(note);
        int slot = insertionSlot(existing, y, h);
        long now = System.currentTimeMillis();
        CardMeta card = new CardMeta(CardMeta.SCHEMA, IdUtil.randomUUID(), title, x, y, w, h,
                                     slot, req.color(), null, false, Map.of(), now, now);
        String fileName = cardRepository.save(card, content, null);
        List<String> fileNames = new ArrayList<>(cardFileNames(note));
        fileNames.add(Math.min(slot, fileNames.size()), fileName);
        note.setCardFileNames(fileNames);
        noteRepository.save(note);
        return new CardView(card, content, note.getTitle());
    }

    private double[] nextFlowPosition(NoteMeta note) {
        double bottom = cards(note).stream()
                                   .mapToDouble(stored -> stored.meta().getY() + stored.meta().getH())
                                   .max()
                                   .orElse(0);
        return new double[]{CardRepository.FLOW_X, bottom <= 0 ? 0 : bottom + CardRepository.FLOW_GAP};
    }

    private static int insertionSlot(List<StoredCard> cards,
                                     double y,
                                     double height) {
        double center = y + height / 2;
        long before = cards.stream()
                           .map(StoredCard::meta)
                           .filter(card -> card.getY() + card.getH() / 2 < center)
                           .count();
        return (int) Math.min(before, cards.size());
    }

    public CardView writeContent(String cardId,
                                 String contentMd,
                                 String baseContentMd) {
        StoredCard stored = requireCard(cardId);
        String current = stored.card().getContent();
        if (baseContentMd != null && !baseContentMd.equals(current)) {
            throw new com.thisnode.plugin.api.ConflictException(
                    "这张卡片已被外部修改（磁盘上的内容与你打开时的不一样），已停止保存");
        }
        CardMeta meta = stored.meta().setUpdatedAt(System.currentTimeMillis());
        String body = contentMd == null ? "" : contentMd;
        cardRepository.save(meta, body, stored.fileName());
        noteRepository.save(stored.note());
        return new CardView(meta, body, stored.note().getTitle());
    }

    public CardMeta update(String cardId,
                           CardUpdate changes) {
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

    public void batchUpdate(BatchCardUpdateRequest req) {
        List<CardUpdate> updates = req.cards();
        for (CardUpdate update : updates) {
            if (update != null && update.id() != null) {
                update(update.id(), update);
            }
        }
    }
}
