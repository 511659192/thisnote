package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.exception.NotFoundException;
import com.thisnode.ext.notes.model.CardPO;
import com.thisnode.ext.notes.model.CardPO.CardMeta;
import com.thisnode.ext.notes.model.NotePO;
import com.thisnode.ext.notes.model.NotesMapper;
import com.thisnode.ext.notes.model.request.BatchUpdateCardsRequest;
import com.thisnode.ext.notes.model.request.CreateCardRequest;
import com.thisnode.ext.notes.model.request.UpdateCardRequest;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.plugin.api.event.CardChangedEvent;
import com.thisnode.plugin.api.exception.ConflictException;
import com.thisnode.plugin.api.notes.dto.CardDTO;
import com.thisnode.plugin.api.notes.dto.CardMetaDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CardService {

    private final CardRepository cardRepository;
    private final NoteRepository noteRepository;
    private final NotesMapper notesMapper;
    private final AttachmentService attachmentService;
    private ApplicationEventPublisher mainEventPublisher;

    @Autowired
    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    public List<CardDTO> listUnowned() throws IOException {
        return cardRepository.listUnowned().stream().map(notesMapper::toCardDTO).toList();
    }

    public List<CardDTO> cards(String noteId,
                               List<String> cardIds) {
        NotePO note = getNote(noteId);
        Set<String> wanted = cardIds == null ? null : new HashSet<>(cardIds);
        return note.getCardIds()
                .stream()
                .filter(cardId -> wanted == null || wanted.contains(cardId))
                .map(cardRepository::getOne)
                .filter(card -> card != null && card.getMeta() != null)
                .map(notesMapper::toCardDTO)
                .toList();
    }

    public CardDTO create(String noteId,
                          CreateCardRequest req,
                          String fixedFileName) {
        if (req.x() == null || req.y() == null) {
            throw new IllegalArgumentException("创建卡片需要 x/y 坐标（页面上的落点）");
        }
        NotePO note = getNote(noteId);
        String content = req.content();
        double x = Math.max(0, req.x());
        double y = Math.max(0, req.y());
        double w = Math.max(CardRepository.MIN_W, req.w());
        double h = Math.max(CardRepository.MIN_H, req.h());

        CardMeta card = new CardMeta().setX(x).setY(y).setW(w).setH(h).setColor(req.color()).setNoteId(note.getNoteId());
        cardRepository.save(card, content, fixedFileName, note.getTitle());

        List<String> cardIds = new ArrayList<>(note.getCardIds());
        cardIds.add(card.getCardId());
        note.setCardIds(cardIds);
        noteRepository.update(note);
        mainEventPublisher.publishEvent(new CardChangedEvent(card.getCardId(), CardChangedEvent.Kind.CREATED));
        log.info("笔记 {} 新建卡片 {}", noteId, card.getCardId());
        return notesMapper.toCardDTO(new CardPO(card, content));
    }

    // Card metadata and body share one Markdown file, so serialize their read-modify-write paths.
    public synchronized CardDTO writeContent(String noteId,
                                             String cardId,
                                             String contentMd,
                                             String baseContentMd) {
        NotePO note = noteContainingCard(noteId, cardId);

        CardPO card = cardRepository.getOne(cardId);
        if (card == null || card.getMeta() == null) {
            throw new NotFoundException("card " + cardId);
        }

        String current = card.getContent();
        if (baseContentMd != null && !baseContentMd.equals(current)) {
            throw new ConflictException("这张卡片已被外部修改（磁盘上的内容与你打开时的不一样），已停止保存");
        }

        CardMeta cardMeta = card.getMeta();
        String body = contentMd == null ? "" : contentMd;
        Set<String> removedAttachments = new HashSet<>(AttachmentService.referencedFileNames(current));
        removedAttachments.removeAll(AttachmentService.referencedFileNames(body));
        cardRepository.update(cardMeta, body, cardId);
        noteRepository.update(note);
        attachmentService.deleteIfUnreferenced(removedAttachments);
        mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.UPDATED));
        log.debug("卡片 {} 内容已保存（{} 字符）", cardId, body.length());
        CardPO saved = cardRepository.getOne(cardId);
        return notesMapper.toCardDTO(saved == null ? new CardPO(cardMeta, body) : saved);
    }

    public synchronized CardMetaDTO update(String noteId,
                                           String cardId,
                                           UpdateCardRequest changes) {
        return notesMapper.toCardMetaDTO(update(getNote(noteId), cardId, changes));
    }

    private CardMeta update(NotePO note,
                            String cardId,
                            UpdateCardRequest changes) {
        noteContainingCard(note, cardId);
        CardPO card = cardRepository.getOne(cardId);
        if (card == null || card.getMeta() == null) {
            throw new NotFoundException("card " + cardId);
        }

        CardMeta meta = card.getMeta();
        double x = changes.x() == null ? meta.getX() : changes.x();
        double y = changes.y() == null ? meta.getY() : changes.y();
        double w = changes.w() == null ? meta.getW() : Math.max(CardRepository.MIN_W, changes.w());
        double h = changes.h() == null ? meta.getH() : Math.max(CardRepository.MIN_H, changes.h());
        meta.setX(x);
        meta.setY(y);
        meta.setW(w);
        meta.setH(h);
        Optional.ofNullable(changes.color()).ifPresent(meta::setColor);
        Optional.ofNullable(changes.status()).ifPresent(meta::setStatus);
        Optional.ofNullable(changes.collapsed()).ifPresent(meta::setCollapsed);
        cardRepository.update(meta, null, cardId);
        mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.UPDATED));
        log.debug("卡片 {} 元数据已更新", cardId);
        return meta;
    }

    public synchronized void batchUpdate(BatchUpdateCardsRequest req) {
        NotePO note = getNote(req.noteId());
        for (UpdateCardRequest update : req.cards()) {
            if (update != null && update.cardId() != null) {
                update(noteContainingCard(note, update.cardId()), update.cardId(), update);
            }
        }
    }

    public void delete(String noteId,
                       String cardId) {
        NotePO note = noteContainingCard(noteId, cardId);
        CardPO card = cardRepository.getOne(cardId);

        Set<String> removedAttachments = AttachmentService.referencedFileNames(card == null ? "" : card.getContent());

        cardRepository.delete(List.of(cardId));
        note.setCardIds(note.getCardIds().stream().filter(id -> !id.equals(cardId)).toList());
        noteRepository.update(note);
        mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.DELETED, removedAttachments));
        log.info("笔记 {} 删除卡片 {}", noteId, cardId);
    }

    private NotePO noteContainingCard(String noteId,
                                      String cardId) {
        return noteContainingCard(getNote(noteId), cardId);
    }

    private NotePO getNote(String noteId) {
        NotePO note = noteRepository.getOne(noteId);
        if (note == null) {
            throw new NotFoundException("node " + noteId);
        }
        return note;
    }

    private NotePO noteContainingCard(NotePO note,
                                      String cardId) {
        if (!note.getCardIds().contains(cardId)) {
            throw new NotFoundException("card " + cardId);
        }
        return note;
    }
}
