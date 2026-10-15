package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.model.Card;
import com.thisnode.ext.notes.model.NoteMeta;
import com.thisnode.ext.notes.model.request.CreateNoteRequest;
import com.thisnode.ext.notes.model.request.NoteListQuery;
import com.thisnode.ext.notes.model.request.UpdateNoteRequest;
import com.thisnode.ext.notes.exception.NotFoundException;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.plugin.api.event.CardChangedEvent;
import com.thisnode.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private final AttachmentService attachmentService;
    private ApplicationEventPublisher mainEventPublisher;

    @Autowired
    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = publisher;
    }

    public String create(CreateNoteRequest req) {
        ApplicationEventPublisher publisher = requireMainEventPublisher();
        NoteMeta noteMeta = new NoteMeta().setTitle("").setType(req.type()).setNotebookId(req.notebookId());
        String noteId = noteRepository.saveNew(noteMeta);
        publisher.publishEvent(new NoteChangedEvent(noteId, NoteChangedEvent.Kind.CREATED, noteMeta.getTitle(), List.of()));
        log.info("创建笔记 {}（type={}，notebook={}）", noteId, req.type(), req.notebookId());
        return noteId;
    }

    public NoteMeta update(String noteId,
                           UpdateNoteRequest req) {
        NoteMeta meta = get(noteId);
        String title = req.title();
        if (title != null && !title.equals(meta.getTitle())) {
            ApplicationEventPublisher publisher = requireMainEventPublisher();
            boolean duplicateTitle = !title.isBlank() && noteRepository.getAll()
                    .stream()
                    .anyMatch(other -> !noteId.equals(other.getNoteId()) && title.equalsIgnoreCase(other.getTitle()));
            if (duplicateTitle) throw new IllegalArgumentException("笔记标题已存在：" + title);

            noteRepository.onNewTitle(meta, title);
            cardRepository.renameFiles(meta.getCardIds(), title);
            for (String cardId : meta.getCardIds()) {
                publisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.UPDATED));
            }
            publisher.publishEvent(new NoteChangedEvent(noteId, NoteChangedEvent.Kind.TITLE_CHANGED, title, List.of()));
            log.info("笔记 {} 标题改为「{}」（联动重命名 {} 张卡片）", noteId, title, meta.getCardIds().size());
        }
        return meta;
    }

    public void delete(String id) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            return;
        }
        ApplicationEventPublisher publisher = requireMainEventPublisher();
        List<String> deletedCardIds = List.copyOf(meta.getCardIds());
        Set<String> removedAttachments = new HashSet<>();
        for (Card card : cardRepository.getAll(deletedCardIds)) {
            removedAttachments.addAll(AttachmentService.referencedFileNames(card.getContent()));
        }
        cardRepository.delete(deletedCardIds);
        noteRepository.delete(id);
        try {
            for (String cardId : deletedCardIds) {
                publisher.publishEvent(new CardChangedEvent(cardId, CardChangedEvent.Kind.DELETED));
            }
            publisher.publishEvent(new NoteChangedEvent(id, NoteChangedEvent.Kind.DELETED, meta.getTitle(), deletedCardIds));
        } finally {
            attachmentService.deleteIfUnreferenced(removedAttachments);
        }
        log.info("删除笔记 {}「{}」及 {} 张卡片", id, meta.getTitle(), deletedCardIds.size());
    }

    public NoteMeta get(String id) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        return meta;
    }

    public List<NoteMeta> list(NoteListQuery q) {
        NoteListQuery query = q == null ? NoteListQuery.defaults() : q;

        List<NoteMeta> matched = new ArrayList<>();
        for (NoteMeta noteMeta : noteRepository.getAll()) {
            if (matches(noteMeta, query)) {
                matched.add(noteMeta);
            }
        }
        int limit = query.limit();
        int offset = query.offset();
        List<NoteMeta> page = new ArrayList<>();
        for (int i = offset; i < matched.size() && page.size() < limit; i++) {
            page.add(matched.get(i));
        }
        return page;
    }

    private ApplicationEventPublisher requireMainEventPublisher() {
        ApplicationEventPublisher publisher = mainEventPublisher;
        if (publisher == null) {
            throw new IllegalStateException("主容器事件发布器尚未就绪");
        }
        return publisher;
    }

    private boolean matches(NoteMeta meta,
                            NoteListQuery query) {
        if (query.type() != null && !query.type().isBlank() && !query.type().equals(meta.getType())) {
            return false;
        }
        if (query.notebookId() != null && !query.notebookId().isBlank() && !query.notebookId().equals(meta.getNotebookId())) {
            return false;
        }
        if (query.text() != null && !query.text().isBlank()) {
            String needle = query.text().toLowerCase(Locale.ROOT);
            boolean titleHit = meta.getTitle() != null && meta.getTitle().toLowerCase(Locale.ROOT).contains(needle);
            boolean cardHit = !titleHit && cardRepository.getAll(meta.getCardIds())
                    .stream()
                    .map(Card::getContent)
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
