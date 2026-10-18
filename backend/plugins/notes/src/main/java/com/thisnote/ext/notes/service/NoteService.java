package com.thisnote.ext.notes.service;

import com.thisnote.ext.notes.exception.NotFoundException;
import com.thisnote.ext.notes.model.CardPO;
import com.thisnote.ext.notes.model.NotePO;
import com.thisnote.ext.notes.model.NotesMapper;
import com.thisnote.ext.notes.model.request.NoteListRequest;
import com.thisnote.ext.notes.model.request.UpdateNoteRequest;
import com.thisnote.ext.notes.repository.BookRepository;
import com.thisnote.ext.notes.repository.CardRepository;
import com.thisnote.ext.notes.repository.NoteRepository;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
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
    private final BookRepository bookRepository;
    private final NotesMapper notesMapper;
    private final AttachmentService attachmentService;
    private ApplicationEventPublisher mainEventPublisher;

    @Autowired
    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    /**
     * 状态重置：把笔记索引、卡片索引与附件索引重写为空（卡片 md 与附件文件保留）。
     */
    public synchronized void resetIndexes() {
        noteRepository.reset();
        cardRepository.reset();
        attachmentService.reset();
        log.info("笔记索引、卡片索引与附件索引已重置");
    }

    public synchronized NoteDTO create(String title,
                                       String bookId,
                                       List<String> cardIds) {
        NotePO note = new NotePO().setTitle(title).setBookId(bookId).setCardIds(cardIds);
        String noteId = noteRepository.save(note);
        mainEventPublisher.publishEvent(new NoteChangedEvent(noteId, NoteChangedEvent.Kind.CREATED, note.getTitle(), cardIds));
        log.info("创建笔记 {}（notebook={}）", noteId, bookId);
        return notesMapper.toNoteDTO(note);
    }

    public NoteDTO update(String noteId,
                          UpdateNoteRequest req) {
        NotePO meta = noteRepository.getOne(noteId);
        if (meta == null) throw new NotFoundException("node " + noteId);

        String title = req.title();
        if (title != null && !title.equals(meta.getTitle())) {
            boolean duplicateTitle = !title.isBlank() && noteRepository.getAll().stream().anyMatch(other -> !noteId.equals(other.getNoteId()) && title.equalsIgnoreCase(other.getTitle()));
            if (duplicateTitle) throw new IllegalArgumentException("笔记标题已存在：" + title);

            noteRepository.update(meta, title);
            cardRepository.renameFiles(meta.getCardIds(), title);
            for (String cardId : meta.getCardIds()) {
                mainEventPublisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.UPDATED));
            }
            mainEventPublisher.publishEvent(new NoteChangedEvent(noteId, NoteChangedEvent.Kind.TITLE_CHANGED, title, List.of()));
            log.info("笔记 {} 标题改为「{}」（联动重命名 {} 张卡片）", noteId, title, meta.getCardIds().size());
        }
        return notesMapper.toNoteDTO(meta);
    }

    /**
     * 批量转入分组：只改笔记的 bookId，卡片与附件不动；索引一次写回，避免逐篇全量重写。
     */
    public synchronized void moveAll(List<String> noteIds, String bookId) {
        if (noteIds == null || noteIds.isEmpty()) return;
        if (bookId == null || bookId.isBlank() || bookRepository.getOne(bookId) == null) {
            throw new IllegalArgumentException("分组不存在：" + bookId);
        }
        List<NotePO> moved = new ArrayList<>();
        for (String noteId : noteIds) {
            NotePO note = noteRepository.getOne(noteId);
            if (note == null) continue;
            note.setBookId(bookId);
            moved.add(note);
        }
        noteRepository.updateAll(moved);
        log.info("将 {} 篇笔记转入分组 {}", moved.size(), bookId);
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

    /**
     * 批量删除：逐篇走与单篇相同的删除流程（卡片、无引用附件一并处理）。
     */
    public synchronized void deleteAll(List<String> noteIds) {
        if (noteIds == null || noteIds.isEmpty()) return;
        int deleted = 0;
        for (String noteId : noteIds) {
            if (noteId == null || noteId.isBlank()) continue;
            delete(noteId);
            deleted++;
        }
        log.info("批量删除笔记 {} 篇", deleted);
    }

    public NoteDTO get(String id) {
        NotePO meta = noteRepository.getOne(id);
        if (meta == null) throw new NotFoundException("node " + id);
        return notesMapper.toNoteDTO(meta);
    }

    public List<NoteDTO> list(NoteListRequest query) {
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
                            NoteListRequest query) {
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
