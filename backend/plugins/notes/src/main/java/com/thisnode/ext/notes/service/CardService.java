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
import java.util.Comparator;
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

    public CardsPageDto cards(String noteId,
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
        if (req.x() == null || req.y() == null) {
            throw new IllegalArgumentException("创建卡片需要 x/y 坐标（页面上的落点）");
        }
        NoteMeta note = noteService.require(noteId);
        String content = req.content();
        String title = req.title();
        double x = Math.max(0, req.x());
        double y = Math.max(0, req.y());
        double w = Math.max(CardRepository.MIN_W, req.w());
        double h = Math.max(CardRepository.MIN_H, req.h());

        CardMeta card = new CardMeta().setId(IdUtil.randomUUID()).setTitle(title).setX(x).setY(y).setW(w).setH(h).setZ(0).setColor(req.color());
        String fileName = cardRepository.save(card, content, null);
        List<String> fileNames = new ArrayList<>(note.getCardFileNames());
        fileNames.add(fileName);
        note.setCardFileNames(fileNames);
        resequence(note);
        return new Card(card, content, fileName);
    }

    /**
     * 按「垂直中心」重排卡片顺序并重算 z（z = 列表序号）。
     *
     * <p>顺序即视觉顺序：{@code page()} 按这个顺序返回（前端 DOM 顺序与分页取数都依赖它），
     * 前端的层叠 zIndex 也直接消费 z。创建与移动（x/y/h 变化）后都走这里，
     * 任意位置建卡、拖卡都不会让顺序与视觉漂移。
     *
     * <p>装载不到的卡片文件名保留在列表尾部，不静默丢弃引用。
     */
    private void resequence(NoteMeta note) {
        List<Card> loaded = cards(note);
        List<Card> ordered = new ArrayList<>(loaded);
        ordered.sort(Comparator.comparingDouble(card -> card.getMeta().getY() + card.getMeta().getH() / 2.0));
        List<String> names = new ArrayList<>(ordered.stream().map(Card::getFileName).toList());
        Set<String> known = new HashSet<>(names);
        for (String fileName : note.getCardFileNames()) {
            if (!known.contains(fileName)) {
                names.add(fileName);
            }
        }
        note.setCardFileNames(names);
        for (int i = 0; i < ordered.size(); i++) {
            CardMeta meta = ordered.get(i).getMeta();
            if (meta.getZ() != i) {
                meta.setZ(i);
                cardRepository.save(ordered.get(i).getFileName(), meta);
            }
        }
        noteRepository.save(note);
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
        NoteMeta note = noteForFile(fileName);
        if (note == null) {
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
        // 位置或高度变了，垂直中心就变了 —— 顺序与 z 需要重排（高度变化也会移动中心点）。
        boolean moved = x != meta.getX() || y != meta.getY() || h != meta.getH();
        meta.setX(x).setY(y).setW(w).setH(h);
        if (changes.title() != null) meta.setTitle(changes.title());
        if (changes.color() != null) meta.setColor(changes.color());
        if (changes.status() != null) meta.setStatus(changes.status());
        if (changes.collapsed() != null) meta.setCollapsed(changes.collapsed());
        meta.setUpdatedAt(System.currentTimeMillis());
        cardRepository.save(fileName, meta);
        if (moved) {
            resequence(note);
        }
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
