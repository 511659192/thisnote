package com.thisnode.ext.notes.service;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.NoteMeta;
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
    private final NotebookService notebookService;
    private final CardRepository cardRepository;

    // ================================================================= create

    public String create(Dtos.CreateNodeRequest req) {
        String id = IdUtil.randomUUID();
        String groupId = req.notebookId() == null ? notebookService.ensureDefaultGroup() : req.notebookId();
        NoteMeta meta = new NoteMeta()
                .setId(id)
                .setTitle(req.title())
                .setType(req.type())
                .setNotebookId(groupId);
        Card.CardMeta firstCard = Card.CardMeta.newCardMeta(
                IdUtil.randomUUID(), 0, 0, "", null, null, System.currentTimeMillis());
        String cardFileName = cardRepository.save(firstCard, req.contentMd(), null);
        meta.setCardFileNames(List.of(cardFileName));
        noteRepository.save(meta);
        return id;
    }

    public void update(String id,
                       Dtos.UpdateNodeRequest req) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        if (req.title() != null) {
            meta.setTitle(req.title());
        }
        if (req.contentMd() != null) {
            replaceNoteContent(meta, req.contentMd());
        }
        meta.setUpdatedAt(System.currentTimeMillis());
        noteRepository.save(meta);
    }

    public void delete(String id) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            return;
        }
        cardRepository.delete(meta.getCardFileNames());
        noteRepository.delete(id);
    }

    public NoteMeta require(String id) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        return meta;
    }

    public Dtos.NoteDetail detail(String id) {
        NoteMeta meta = require(id);
        String content = cardRepository.getJoinedContents(meta.getCardFileNames());
        List<String> cardIds = new ArrayList<>();
        for (String fileName : meta.getCardFileNames()) {
            Card card = cardRepository.get(fileName);
            if (card != null && card.getMeta() != null) {
                cardIds.add(card.getMeta().getId());
            }
        }
        return new Dtos.NoteDetail(meta.getId(),
                                   meta.getTitle(),
                                   content,
                                   meta.getType(),
                                   cardIds,
                                   notebookService.notebooksOf(id),
                                   meta.getCreatedAt(),
                                   meta.getUpdatedAt());
    }

    public record Query(String notebookId, String type, String text, int limit, int offset) {
        public Query {
            limit = Math.max(1, Math.min(limit <= 0 ? 500 : limit, 2000));
            offset = Math.max(0, offset);
        }

        public static Query defaults() {
            return new Query(null, null, null, 500, 0);
        }
    }

    public List<NoteMeta> list(Query q) {
        Query query = q == null ? Query.defaults() : q;

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

    private void replaceNoteContent(NoteMeta note, String contentMd) {
        List<String> fileNames = new ArrayList<>(note.getCardFileNames());
        if (fileNames.isEmpty()) {
            Card.CardMeta card = Card.CardMeta.newCardMeta(
                    IdUtil.randomUUID(), 0, 0, "", null, null, System.currentTimeMillis());
            fileNames.add(cardRepository.save(card, contentMd, null));
            note.setCardFileNames(fileNames);
            return;
        }
        for (int i = 0; i < fileNames.size(); i++) {
            Card card = cardRepository.get(fileNames.get(i));
            if (card == null || card.getMeta() == null) {
                continue;
            }
            cardRepository.save(card.getMeta(), i == 0 ? contentMd : "", fileNames.get(i));
        }
    }

    private boolean matches(NoteMeta meta,
                            Query query) {
        if (query.type() != null && !query.type().isBlank() && !query.type().equals(meta.getType())) {
            return false;
        }
        if (query.notebookId() != null && !query.notebookId().isBlank() && !query.notebookId().equals(meta.getNotebookId())) {
            return false;
        }
        if (query.text() != null && !query.text().isBlank()) {
            String needle = query.text().toLowerCase(Locale.ROOT);
            String body = cardRepository.getJoinedContents(meta.getCardFileNames()).toLowerCase(Locale.ROOT);
            boolean hit = (meta.getTitle() != null && meta.getTitle().toLowerCase(Locale.ROOT).contains(needle)) || body.contains(needle);
            if (!hit) {
                return false;
            }
        }
        return true;
    }


    public static class NotFoundException
            extends com.thisnode.plugin.api.NotFoundException {
        public NotFoundException(String what) {
            super("未找到：" + what);
        }
    }

}
