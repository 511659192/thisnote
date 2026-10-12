package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.BatchUpdateCardsRequest;
import com.thisnode.ext.notes.api.dto.CreateCardRequest;
import com.thisnode.ext.notes.api.dto.NoteDto;
import com.thisnode.ext.notes.api.dto.UpdateCardRequest;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.exception.NotFoundException;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class CardService {

    private final CardRepository cardRepository;
    private final NoteRepository noteRepository;
    private final NoteService noteService;

    private List<Card> cards(NoteMeta note) {
        return note.getCardIds().stream()
                .map(cardRepository::getOne)
                .filter(card -> card != null && card.getMeta() != null)
                .toList();
    }

    public NoteDto cards(String noteId,
                         List<String> cardIds) {
        NoteMeta noteMeta = noteService.get(noteId);
        List<Card> cards;
        if (cardIds == null) {
            cards = cards(noteMeta);
        } else {
            Set<String> wanted = new HashSet<>(cardIds);
            cards = noteMeta.getCardIds().stream()
                    .filter(wanted::contains)
                    .map(cardRepository::getOne)
                    .filter(card -> card != null && card.getMeta() != null)
                    .toList();
        }
        return new NoteDto(noteMeta, cards);
    }

    public Card create(String noteId,
                       CreateCardRequest req) {
        if (req.x() == null || req.y() == null) {
            throw new IllegalArgumentException("创建卡片需要 x/y 坐标（页面上的落点）");
        }
        NoteMeta noteMeta = noteService.get(noteId);
        String content = req.content();
        String title = req.title();
        double x = Math.max(0, req.x());
        double y = Math.max(0, req.y());
        double w = Math.max(CardRepository.MIN_W, req.w());
        double h = Math.max(CardRepository.MIN_H, req.h());

        CardMeta card = new CardMeta().setTitle(title).setX(x).setY(y).setW(w).setH(h).setColor(req.color());
        cardRepository.saveNew(card, content, noteMeta.getTitle());

        List<String> cardIds = new ArrayList<>(noteMeta.getCardIds());
        cardIds.add(card.getId());
        noteMeta.setCardIds(cardIds);
        noteRepository.save(noteMeta);
        return new Card(card, content);
    }

    public Card writeContent(String noteId,
                             String cardId,
                             String contentMd,
                             String baseContentMd) {
        NoteMeta note = noteContainingCard(noteId, cardId);

        Card card = cardRepository.getOne(cardId);
        if (card == null || card.getMeta() == null) {
            throw new NotFoundException("card " + cardId);
        }

        String current = card.getContent();
        if (baseContentMd != null && !baseContentMd.equals(current)) {
            throw new com.thisnode.plugin.api.ConflictException("这张卡片已被外部修改（磁盘上的内容与你打开时的不一样），已停止保存");
        }

        CardMeta cardMeta = card.getMeta();
        String body = contentMd == null ? "" : contentMd;
        cardRepository.save(cardMeta, body, cardId);
        noteRepository.save(note);
        Card saved = cardRepository.getOne(cardId);
        return saved == null ? new Card(cardMeta, body) : saved;
    }

    public CardMeta update(String noteId,
                           String cardId,
                           UpdateCardRequest changes) {
        return update(noteService.get(noteId), cardId, changes);
    }

    private CardMeta update(NoteMeta noteMeta,
                            String cardId,
                            UpdateCardRequest changes) {
        noteContainingCard(noteMeta, cardId);
        Card card = cardRepository.getOne(cardId);
        if (card == null || card.getMeta() == null) {
            throw new NotFoundException("card " + cardId);
        }

        CardMeta meta = card.getMeta();
        double x = changes.x() == null ? meta.getX() : changes.x();
        double y = changes.y() == null ? meta.getY() : changes.y();
        double w = changes.w() == null ? meta.getW() : Math.max(CardRepository.MIN_W, changes.w());
        double h = changes.h() == null ? meta.getH() : Math.max(CardRepository.MIN_H, changes.h());
        meta.setX(x).setY(y).setW(w).setH(h);
        Optional.ofNullable(changes.title()).ifPresent(meta::setTitle);
        Optional.ofNullable(changes.color()).ifPresent(meta::setColor);
        Optional.ofNullable(changes.status()).ifPresent(meta::setStatus);
        Optional.ofNullable(changes.collapsed()).ifPresent(meta::setCollapsed);
        cardRepository.save(meta, cardId);
        return meta;
    }

    public void batchUpdate(BatchUpdateCardsRequest req) {
        NoteMeta note = noteService.get(req.noteId());
        for (UpdateCardRequest update : req.cards()) {
            if (update != null && update.cardId() != null) {
                update(noteContainingCard(note, update.cardId()), update.cardId(), update);
            }
        }
    }

    public void delete(String noteId, String cardId) {
        NoteMeta note = noteContainingCard(noteId, cardId);

        cardRepository.delete(List.of(cardId));
        note.setCardIds(note.getCardIds().stream()
                .filter(id -> !id.equals(cardId))
                .toList());
        noteRepository.save(note);
    }

    private NoteMeta noteContainingCard(String noteId, String cardId) {
        return noteContainingCard(noteService.get(noteId), cardId);
    }

    private NoteMeta noteContainingCard(NoteMeta note, String cardId) {
        if (!note.getCardIds().contains(cardId)) {
            throw new NotFoundException("card " + cardId);
        }
        return note;
    }
}
