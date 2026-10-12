package com.thisnode.ext.notes.service;

import cn.hutool.core.util.IdUtil;
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
import com.thisnode.ext.notes.util.NoteTitleDefaults;
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
        return note.getCardFileNames().stream().map(cardRepository::getOne).filter(card -> card != null && card.getMeta() != null).toList();
    }

    public NoteDto cards(String noteId,
                         List<String> requestedFileNames) {
        NoteMeta noteMeta = noteService.get(noteId);
        List<Card> cards;
        if (requestedFileNames == null) {
            cards = cards(noteMeta);
        } else {
            Set<String> wanted = new HashSet<>(requestedFileNames);
            cards = noteMeta.getCardFileNames().stream().filter(fileName -> wanted.contains(fileName)).map(cardRepository::getOne).toList();
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

        CardMeta card = new CardMeta().setId(IdUtil.randomUUID()).setTitle(title).setX(x).setY(y).setW(w).setH(h).setColor(req.color());
        String fileName = cardRepository.save(card, content, null);

        List<String> fileNames = new ArrayList<>(noteMeta.getCardFileNames());
        fileNames.add(fileName);
        noteMeta.setCardFileNames(fileNames);
        noteRepository.save(noteMeta);
        return new Card(card, content, fileName);
    }

    public Card writeContent(String noteId,
                             String cardFileName,
                             String contentMd,
                             String baseContentMd) {
        NoteMeta note = noteContainingCard(noteId, cardFileName);

        Card card = cardRepository.getOne(cardFileName);
        if (card == null || card.getMeta() == null) {
            throw new NotFoundException("card " + cardFileName);
        }

        String current = card.getContent();
        if (baseContentMd != null && !baseContentMd.equals(current)) {
            throw new com.thisnode.plugin.api.ConflictException("这张卡片已被外部修改（磁盘上的内容与你打开时的不一样），已停止保存");
        }

        CardMeta cardMeta = card.getMeta();
        String body = contentMd == null ? "" : contentMd;
        cardRepository.save(cardMeta, body, cardFileName);
        if (NoteTitleDefaults.isPlaceholderTitle(note.getTitle())) {
            String generatedTitle = noteService.generatedTitleFromCards(note);
            if (generatedTitle != null) {
                note.setTitle(generatedTitle);
            }
        }
        noteRepository.save(note);
        return new Card(cardMeta, body, cardFileName);
    }

    public CardMeta update(String noteId,
                           String fileName,
                           UpdateCardRequest changes) {
        return update(noteService.get(noteId), fileName, changes);
    }

    private CardMeta update(NoteMeta noteMeta,
                            String fileName,
                            UpdateCardRequest changes) {
        noteContainingCard(noteMeta, fileName);
        Card card = cardRepository.getOne(fileName);
        if (card == null || card.getMeta() == null) {
            throw new NotFoundException("card " + fileName);
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
        cardRepository.save(meta, fileName);
        return meta;
    }

    public void batchUpdate(BatchUpdateCardsRequest req) {
        NoteMeta note = noteService.get(req.noteId());
        for (UpdateCardRequest update : req.cards()) {
            if (update != null && update.fileName() != null) {
                update(noteContainingCard(note, update.fileName()), update.fileName(), update);
            }
        }
    }

    public void delete(String noteId, String fileName) {
        NoteMeta note = noteContainingCard(noteId, fileName);

        cardRepository.delete(List.of(fileName));
        note.setCardFileNames(note.getCardFileNames().stream()
                .filter(name -> !name.equals(fileName))
                .toList());
        noteRepository.save(note);
    }

    private NoteMeta noteContainingCard(String noteId, String fileName) {
        return noteContainingCard(noteService.get(noteId), fileName);
    }

    private NoteMeta noteContainingCard(NoteMeta note, String fileName) {
        if (!note.getCardFileNames().contains(fileName)) {
            throw new NotFoundException("card " + fileName);
        }
        return note;
    }
}
