package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.CreateNoteRequest;
import com.thisnode.ext.notes.api.dto.NoteListQuery;
import com.thisnode.ext.notes.api.dto.UpdateNoteRequest;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.exception.NotFoundException;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
public class NoteService {

    private final NoteRepository noteRepository;
    private final CardRepository cardRepository;

    public String create(CreateNoteRequest req) {
        NoteMeta noteMeta = new NoteMeta().setTitle("").setType(req.type()).setNotebookId(req.notebookId());
        return noteRepository.saveNew(noteMeta);
    }

    public NoteMeta update(String noteId,
                           UpdateNoteRequest req) {
        NoteMeta meta = get(noteId);
        if (req.title() != null && !req.title().equals(meta.getTitle())) {
            noteRepository.saveRenamed(meta, req.title());
        }
        return meta;
    }

    public void delete(String id) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            return;
        }
        cardRepository.delete(meta.getCardIds());
        noteRepository.delete(id);
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
        matched.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));

        int limit = query.limit();
        int offset = query.offset();
        List<NoteMeta> page = new ArrayList<>();
        for (int i = offset; i < matched.size() && page.size() < limit; i++) {
            page.add(matched.get(i));
        }
        return page;
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
