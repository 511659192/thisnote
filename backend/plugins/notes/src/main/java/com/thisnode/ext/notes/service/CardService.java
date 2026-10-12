package com.thisnode.ext.notes.service;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.api.dto.BatchUpdateCardsRequest;
import com.thisnode.ext.notes.api.dto.CardsPageDto;
import com.thisnode.ext.notes.api.dto.CreateCardRequest;
import com.thisnode.ext.notes.api.dto.UpdateCardRequest;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.exception.NotFoundException;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
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

    private List<Card> cards(NoteMeta note) {
        return note.getCardFileNames().stream().map(cardRepository::getOne).filter(card -> card != null && card.getMeta() != null).toList();
    }

    private NoteMeta noteForFile(String fileName) {
        return noteRepository.getAll().stream().filter(note -> note.getCardFileNames().contains(fileName)).findFirst().get();
    }

    public CardsPageDto page(String noteId,
                             List<String> requestedFileNames) {
        NoteMeta note = noteService.require(noteId);
        List<Card> rows;
        if (requestedFileNames == null) {
            rows = cards(note);
        } else {
            Set<String> wanted = new HashSet<>(requestedFileNames);
            rows = new ArrayList<>(wanted.size());
            for (String fileName : note.getCardFileNames()) {
                if (!wanted.contains(fileName)) {
                    continue;
                }
                Card card = cardRepository.getOne(fileName);
                if (card != null && card.getMeta() != null) {
                    rows.add(card);
                }
            }
        }
        return new CardsPageDto(note.getId(), note.getTitle(), rows, note.getUpdatedAt());
    }

    public Card create(String noteId,
                       CreateCardRequest req) {
        NoteMeta note = noteService.require(noteId);
        String content = req.content();
        String title = req.title();
        double[] flow = nextFlowPosition(note);
        double x = req.x() == null ? flow[0] : Math.max(0, req.x());
        double y = req.y() == null ? flow[1] : Math.max(0, req.y());
        double w = Math.max(CardRepository.MIN_W, req.w());
        double h = Math.max(CardRepository.MIN_H, req.h());

        List<Card> existing = cards(note);
        int slot = insertionSlot(existing, y, h);
        CardMeta card = new CardMeta().setId(IdUtil.randomUUID()).setTitle(title).setX(x).setY(y).setW(w).setH(h).setZ(slot).setColor(req.color());
        String fileName = cardRepository.save(card, content, null);
        List<String> fileNames = new ArrayList<>(note.getCardFileNames());
        fileNames.add(Math.min(slot, fileNames.size()), fileName);
        note.setCardFileNames(fileNames);
        noteRepository.save(note);
        return new Card(card, content, fileName);
    }

    private double[] nextFlowPosition(NoteMeta note) {
        double bottom = cards(note).stream().mapToDouble(card -> card.getMeta().getY() + card.getMeta().getH()).max().orElse(0);
        return new double[]{CardRepository.FLOW_X, bottom <= 0 ? 0 : bottom + CardRepository.FLOW_GAP};
    }

    private static int insertionSlot(List<Card> cards,
                                     double y,
                                     double height) {
        double center = y + height / 2;
        int slot = 0;
        for (Card card : cards) {
            CardMeta meta = card.getMeta();
            if (meta.getY() + meta.getH() / 2 < center) {
                slot++;
            }
        }
        return Math.min(slot, cards.size());
    }

    public Card writeContent(String cardFileName,
                             String contentMd,
                             String baseContentMd) {
        NoteMeta note = noteForFile(cardFileName);
        if (note == null) {
            throw new NotFoundException("card " + cardFileName);
        }
        Card card = cardRepository.getOne(cardFileName);
        if (card == null || card.getMeta() == null) {
            throw new NotFoundException("card " + cardFileName);
        }
        String current = card.getContent();
        if (baseContentMd != null && !baseContentMd.equals(current)) {
            throw new com.thisnode.plugin.api.ConflictException("这张卡片已被外部修改（磁盘上的内容与你打开时的不一样），已停止保存");
        }
        CardMeta meta = card.getMeta().setUpdatedAt(System.currentTimeMillis());
        String body = contentMd == null ? "" : contentMd;
        cardRepository.save(meta, body, cardFileName);
        note.setUpdatedAt(System.currentTimeMillis());
        noteRepository.save(note);
        return new Card(meta, body, cardFileName);
    }

    public CardMeta update(String fileName,
                           UpdateCardRequest changes) {
        if (noteForFile(fileName) == null) {
            throw new NotFoundException("card " + fileName);
        }
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
        if (changes.title() != null) meta.setTitle(changes.title());
        if (changes.color() != null) meta.setColor(changes.color());
        if (changes.status() != null) meta.setStatus(changes.status());
        if (changes.collapsed() != null) meta.setCollapsed(changes.collapsed());
        meta.setUpdatedAt(System.currentTimeMillis());
        cardRepository.save(fileName, meta);
        return meta;
    }

    public void batchUpdate(BatchUpdateCardsRequest req) {
        List<UpdateCardRequest> updates = req.cards();
        for (UpdateCardRequest update : updates) {
            if (update != null && update.fileName() != null) {
                update(update.fileName(), update);
            }
        }
    }
}
