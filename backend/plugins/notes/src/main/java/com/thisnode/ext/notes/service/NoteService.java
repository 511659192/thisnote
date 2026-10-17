package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.exception.NotFoundException;
import com.thisnode.ext.notes.model.CardPO;
import com.thisnode.ext.notes.model.NotePO;
import com.thisnode.ext.notes.model.NotesMapper;
import com.thisnode.ext.notes.model.request.CreateNoteRequest;
import com.thisnode.ext.notes.model.request.NoteListQuery;
import com.thisnode.ext.notes.model.request.UpdateNoteRequest;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.plugin.api.event.CardChangedEvent;
import com.thisnode.plugin.api.event.NoteChangedEvent;
import com.thisnode.plugin.api.notes.dto.NoteDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Note aggregate: CRUD and list queries.
 *
 * <p>Coordinates note metadata and card-file operations.
 *
 * <p>与数据库时代相比少了一条不变量：不再有"同步镜像"这一步。内容与元数据本来就落在笔记目录里
 * （卡片 md 承载正文、meta 承载归属与几何），文件就是权威，没有第二份需要追平。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NoteService {

    private final NoteRepository noteRepository;
    private final CardRepository cardRepository;
    private final NotesMapper notesMapper;
    private final AttachmentService attachmentService;
    private ApplicationEventPublisher mainEventPublisher;

    @Autowired
    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    public NoteDTO create(CreateNoteRequest req) {
        return create("", req.bookId(), List.of());
    }

    public synchronized NoteDTO create(String title,
                                           String bookId,
                                           List<String> cardIds) {
        if (cardIds != null && cardIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("笔记包含无效的卡片 ID");
        }

        NotePO note = new NotePO().setTitle(title).setBookId(bookId).setCardIds(cardIds);
        String noteId = noteRepository.saveNew(note);
        mainEventPublisher.publishEvent(new NoteChangedEvent(noteId, NoteChangedEvent.Kind.CREATED, note.getTitle(), cardIds));
        log.info("创建笔记 {}（notebook={}）", noteId, bookId);
        return notesMapper.toNoteDTO(note);
    }

    public NoteDTO update(String noteId,
                              UpdateNoteRequest req) {
        NotePO meta = getPO(noteId);
        String title = req.title();
        if (title != null && !title.equals(meta.getTitle())) {
            boolean duplicateTitle = !title.isBlank() && noteRepository.getAll().stream().anyMatch(other -> !noteId.equals(other.getNoteId()) && title.equalsIgnoreCase(other.getTitle()));
            if (duplicateTitle) throw new IllegalArgumentException("笔记标题已存在：" + title);

            noteRepository.newTitle(meta, title);
            cardRepository.renameFiles(meta.getCardIds(), title);
            for (String cardId : meta.getCardIds()) {
                mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.UPDATED));
            }
            mainEventPublisher.publishEvent(new NoteChangedEvent(noteId, NoteChangedEvent.Kind.TITLE_CHANGED, title, List.of()));
            log.info("笔记 {} 标题改为「{}」（联动重命名 {} 张卡片）", noteId, title, meta.getCardIds().size());
        }
        return notesMapper.toNoteDTO(meta);
    }

    public void delete(String id) {
        NotePO meta = noteRepository.getOne(id);
        if (meta == null) {
            return;
        }
        List<String> deletedCardIds = List.copyOf(meta.getCardIds());
        Set<String> removedAttachments = new HashSet<>();
        for (CardPO card : cardRepository.getAll(deletedCardIds)) {
            removedAttachments.addAll(AttachmentService.referencedFileNames(card.getContent()));
        }
        cardRepository.delete(deletedCardIds);
        noteRepository.delete(id);
        try {
            for (String cardId : deletedCardIds) {
                mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.DELETED));
            }
            mainEventPublisher.publishEvent(new NoteChangedEvent(id, NoteChangedEvent.Kind.DELETED, meta.getTitle(), deletedCardIds));
        } finally {
            attachmentService.deleteIfUnreferenced(removedAttachments);
        }
        log.info("删除笔记 {}「{}」及 {} 张卡片", id, meta.getTitle(), deletedCardIds.size());
    }

    public NoteDTO get(String id) {
        return notesMapper.toNoteDTO(getPO(id));
    }

    private NotePO getPO(String id) {
        NotePO meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        return meta;
    }

    public List<NoteDTO> list(NoteListQuery q) {
        NoteListQuery query = q == null ? NoteListQuery.defaults() : q;

        List<NotePO> matched = new ArrayList<>();
        for (NotePO note : noteRepository.getAll()) {
            if (matches(note, query)) {
                matched.add(note);
            }
        }
        int limit = query.limit();
        int offset = query.offset();
        List<NotePO> page = new ArrayList<>();
        for (int i = offset; i < matched.size() && page.size() < limit; i++) {
            page.add(matched.get(i));
        }
        return page.stream().map(notesMapper::toNoteDTO).toList();
    }

    private boolean matches(NotePO meta,
                            NoteListQuery query) {
        if (query.bookId() != null && !query.bookId().isBlank() && !query.bookId().equals(meta.getBookId())) {
            return false;
        }
        if (query.text() != null && !query.text().isBlank()) {
            String needle = query.text().toLowerCase(Locale.ROOT);
            boolean titleHit = meta.getTitle() != null && meta.getTitle().toLowerCase(Locale.ROOT).contains(needle);
            boolean cardHit = !titleHit && cardRepository.getAll(meta.getCardIds())
                    .stream()
                    .map(CardPO::getContent)
                    .filter(content -> content != null)
                    .map(content -> content.toLowerCase(Locale.ROOT))
                    .anyMatch(content -> content.contains(needle));
            if (!titleHit && !cardHit) {
                return false;
            }
        }
        return true;
    }

}
