package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.model.CardPO;
import com.thisnode.ext.notes.model.NotesMapper;
import com.thisnode.ext.notes.model.NoteMetaPO;
import com.thisnode.ext.notes.model.request.BatchUpdateCardsRequest;
import com.thisnode.ext.notes.model.request.CreateCardRequest;
import com.thisnode.ext.notes.model.request.UpdateCardRequest;
import com.thisnode.ext.notes.model.CardPO.CardMeta;
import com.thisnode.ext.notes.exception.NotFoundException;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.plugin.api.event.CardChangedEvent;
import com.thisnode.plugin.api.exception.ConflictException;
import com.thisnode.plugin.api.notes.dto.CardDTO;
import com.thisnode.plugin.api.notes.dto.CardMetaDTO;
import com.thisnode.plugin.api.notes.dto.CardResponseDTO;
import com.thisnode.plugin.api.notes.dto.NoteDTO;
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

    private List<CardPO> cards(NoteMetaPO note) {
        return note.getCardIds().stream().map(cardRepository::getOne).filter(card -> card != null && card.getMeta() != null).toList();
    }

    public List<CardDTO> listCards() throws IOException {
        return cardRepository.listAllFiles().stream().map(notesMapper::toCardDTO).toList();
    }

    public NoteDTO cards(String noteId,
                         List<String> cardIds) {
        NoteMetaPO noteMeta = getNoteMeta(noteId);
        List<CardPO> cards;
        if (cardIds == null) {
            cards = cards(noteMeta);
        } else {
            Set<String> wanted = new HashSet<>(cardIds);
            cards = noteMeta.getCardIds().stream().filter(wanted::contains).map(cardRepository::getOne).filter(card -> card != null && card.getMeta() != null).toList();
        }
        return notesMapper.toNoteDTO(noteMeta, cards);
    }

    public CardResponseDTO create(String noteId,
                       CreateCardRequest req) {
        return create(noteId, req, null);
    }

    public CardResponseDTO create(String noteId,
                                  CreateCardRequest req,
                                  String fixedFileName) {
        if (req.x() == null || req.y() == null) {
            throw new IllegalArgumentException("创建卡片需要 x/y 坐标（页面上的落点）");
        }
        NoteMetaPO noteMeta = getNoteMeta(noteId);
        String content = req.content();
        double x = Math.max(0, req.x());
        double y = Math.max(0, req.y());
        double w = Math.max(CardRepository.MIN_W, req.w());
        double h = Math.max(CardRepository.MIN_H, req.h());

        CardMeta card = new CardMeta()
        .setX(x)
        .setY(y)
        .setW(w)
        .setH(h)
        .setColor(req.color())
        .setNoteId(noteMeta.getNoteId());
        if (fixedFileName == null) {
            cardRepository.saveNew(card, content, noteMeta.getTitle());
        } else {
            card.setCardId(UUID.randomUUID().toString());
            card.setSourceFileName(fixedFileName);
            cardRepository.saveWithMetadata(fixedFileName, card.getCardId(), CardPO.compose(card, content));
        }

        List<String> cardIds = new ArrayList<>(noteMeta.getCardIds());
        cardIds.add(card.getCardId());
        noteMeta.setCardIds(cardIds);
        noteRepository.save(noteMeta);
        mainEventPublisher.publishEvent(new CardChangedEvent(card.getCardId(), CardChangedEvent.Kind.CREATED));
        log.info("笔记 {} 新建卡片 {}", noteId, card.getCardId());
        return notesMapper.toCardResponseDTO(new CardPO(card, content));
    }

    // Card metadata and body share one Markdown file, so serialize their read-modify-write paths.
    public synchronized CardResponseDTO writeContent(String noteId,
                                          String cardId,
                                          String contentMd,
                                          String baseContentMd) {
        NoteMetaPO noteMeta = noteContainingCard(noteId, cardId);

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
        cardRepository.save(cardMeta, body, cardId);
        noteRepository.save(noteMeta);
        attachmentService.deleteIfUnreferenced(removedAttachments);
        mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.UPDATED));
        log.debug("卡片 {} 内容已保存（{} 字符）", cardId, body.length());
        CardPO saved = cardRepository.getOne(cardId);
        return notesMapper.toCardResponseDTO(saved == null ? new CardPO(cardMeta, body) : saved);
    }

    public synchronized CardMetaDTO update(String noteId,
                                        String cardId,
                                        UpdateCardRequest changes) {
        return notesMapper.toCardMetaDTO(update(getNoteMeta(noteId), cardId, changes));
    }

    private CardMeta update(NoteMetaPO noteMeta,
                            String cardId,
                            UpdateCardRequest changes) {
        noteContainingCard(noteMeta, cardId);
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
        cardRepository.save(meta, cardId);
        mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.UPDATED));
        log.debug("卡片 {} 元数据已更新", cardId);
        return meta;
    }

    public synchronized void batchUpdate(BatchUpdateCardsRequest req) {
        NoteMetaPO note = getNoteMeta(req.noteId());
        for (UpdateCardRequest update : req.cards()) {
            if (update != null && update.cardId() != null) {
                update(noteContainingCard(note, update.cardId()), update.cardId(), update);
            }
        }
    }

    public void delete(String noteId,
                       String cardId) {
        NoteMetaPO note = noteContainingCard(noteId, cardId);
        CardPO card = cardRepository.getOne(cardId);

        Set<String> removedAttachments = AttachmentService.referencedFileNames(card == null ? "" : card.getContent());

        cardRepository.delete(List.of(cardId));
        note.setCardIds(note.getCardIds().stream().filter(id -> !id.equals(cardId)).toList());
        noteRepository.save(note);
        mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.DELETED, removedAttachments));
        log.info("笔记 {} 删除卡片 {}", noteId, cardId);
    }

    private NoteMetaPO noteContainingCard(String noteId,
                                        String cardId) {
        return noteContainingCard(getNoteMeta(noteId), cardId);
    }

    private NoteMetaPO getNoteMeta(String noteId) {
        NoteMetaPO noteMeta = noteRepository.getOne(noteId);
        if (noteMeta == null) {
            throw new NotFoundException("node " + noteId);
        }
        return noteMeta;
    }

    private NoteMetaPO noteContainingCard(NoteMetaPO note,
                                        String cardId) {
        if (!note.getCardIds().contains(cardId)) {
            throw new NotFoundException("card " + cardId);
        }
        return note;
    }
}
