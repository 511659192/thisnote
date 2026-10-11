package com.thisnode.ext.notes.service;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.util.MarkdownUtils;
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
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.contentMd() == null ? "" : req.contentMd();
        String type = req.type() == null || req.type().isBlank() ? "note" : req.type();
        String id = noteRepository.insert(titleOf(title, content), type);
        NoteMeta meta = noteRepository.getOne(id);
        Card.CardMeta firstCard = Card.CardMeta.newCardMeta(
                IdUtil.randomUUID(), 0, 0, "", null, null, System.currentTimeMillis());
        String cardFileName = cardRepository.save(meta.getTitle(), firstCard, content, null);
        meta.setCardFileNames(List.of(cardFileName));
        noteRepository.save(meta);

        String groupId = req.notebookId() != null && !req.notebookId().isBlank() ? req.notebookId() : notebookService.ensureDefaultGroup();
        notebookService.addNodeToNotebook(id, groupId);
        return id;
    }

    /**
     * 标题兜底：没写标题就用正文首个标题，再没有才叫"无标题笔记"。
     */
    private static String titleOf(String title,
                                  String contentMd) {
        if (title != null && !title.isBlank()) {
            return title;
        }
        String fromHeading = MarkdownUtils.firstHeading(contentMd);
        return fromHeading != null ? fromHeading : "无标题笔记";
    }

    public void update(String id,
                       Dtos.UpdateNodeRequest req) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        String content = cardRepository.getJoinedContents(meta.getCardFileNames());
        boolean contentChanged = false;
        String oldTitle = meta.getTitle();
        boolean renamed = req.title() != null && !req.title().equals(oldTitle);
        String nextTitle = oldTitle;
        if (renamed) {
            nextTitle = titleOf(req.title(), content);
            contentChanged = true;
        }
        String nextContent = content;
        if (req.contentMd() != null && !req.contentMd().equals(content)) {
            nextContent = req.contentMd();
            contentChanged = true;
        }
        meta.setTitle(nextTitle);
        meta.setUpdatedAt(System.currentTimeMillis());
        noteRepository.save(meta);

        if (Boolean.TRUE.equals(req.clearNotebook())) {
            // 单归属且必有归属：「移出分组」= 回到默认分组，不是变成无归属
            notebookService.setExclusiveGroup(id, notebookService.ensureDefaultGroup());
        } else if (req.notebookId() != null && !req.notebookId().isBlank()) {
            notebookService.setExclusiveGroup(id, req.notebookId());
        }
        if (contentChanged) {
            // 整篇正文替换（写回首卡）。
            replaceNoteContent(meta, nextContent);
        }
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

        int limit = Math.max(1, Math.min(query.limit() <= 0 ? 500 : query.limit(), 2000));
        int offset = Math.max(0, query.offset());
        List<NoteMeta> page = new ArrayList<>();
        for (int i = offset; i < matched.size() && page.size() < limit; i++) {
            page.add(matched.get(i));
        }
        return page;
    }

    private void replaceNoteContent(NoteMeta note, String contentMd) {
        String body = contentMd == null ? "" : contentMd;
        List<String> fileNames = new ArrayList<>(note.getCardFileNames());
        if (fileNames.isEmpty()) {
            Card.CardMeta card = Card.CardMeta.newCardMeta(
                    IdUtil.randomUUID(), 0, 0, "", null, null, System.currentTimeMillis());
            fileNames.add(cardRepository.save(note.getTitle(), card, body, null));
            note.setCardFileNames(fileNames);
            noteRepository.save(note);
            return;
        }
        for (int i = 0; i < fileNames.size(); i++) {
            Card card = cardRepository.get(fileNames.get(i));
            if (card == null || card.getMeta() == null) {
                continue;
            }
            cardRepository.save(note.getTitle(), card.getMeta(), i == 0 ? body : "", fileNames.get(i));
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
