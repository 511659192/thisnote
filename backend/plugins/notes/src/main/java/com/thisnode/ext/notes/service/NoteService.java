package com.thisnode.ext.notes.service;

import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.repository.CardRepository;
import com.thisnode.ext.notes.repository.NoteRepository;
import com.thisnode.ext.notes.repository.NotebookRepository;
import com.thisnode.ext.notes.util.JsonFileUtils;
import com.thisnode.ext.notes.util.MarkdownUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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
    private final NotebookRepository notebookRepository;
    private final CardRepository cardRepository;

    // ================================================================= create

    public String create(Dtos.CreateNodeRequest req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.contentMd() == null ? "" : req.contentMd();
        String type = req.type() == null || req.type().isBlank() ? "note" : req.type();
        String id = noteRepository.insert(titleOf(title, content), type);
        // 编排：首卡落盘（一篇笔记至少一张卡；正文只有一份，在卡片里）。
        cardRepository.putCard(id, Card.CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null, System.currentTimeMillis()), content);

        // 笔记必须归属一个分组：界面侧在某个分组里新建时会带上当前分组，没带就落默认分组。
        // 不变量在后端收口，客户端（含脚本、导入）不可能造出无归属的笔记。
        String groupId = req.notebookId() != null && !req.notebookId().isBlank() ? req.notebookId() : notebookRepository.ensureDefaultGroup();
        notebookRepository.addNodeToNotebook(id, groupId);
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

    // ================================================================= update

    public void update(String id,
                       Dtos.UpdateNodeRequest req) {
        NoteMeta meta = noteRepository.getNoteMeta(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        String content = noteRepository.text(meta);
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
        JsonFileUtils.write(Path.of(noteRepository.getRoot().toString(), "meta", id + ".json"), meta);

        if (Boolean.TRUE.equals(req.clearNotebook())) {
            // 单归属且必有归属：「移出分组」= 回到默认分组，不是变成无归属
            notebookRepository.setExclusiveGroup(id, notebookRepository.ensureDefaultGroup());
        } else if (req.notebookId() != null && !req.notebookId().isBlank()) {
            notebookRepository.setExclusiveGroup(id, req.notebookId());
        }
        if (contentChanged) {
            // 整篇正文替换（写回首卡）。
            noteRepository.replaceNoteContent(id, nextContent);
        }
    }

    // ================================================================= delete

    /**
     * 硬删除：卡片 md 与笔记 meta 一起消失。
     */
    public void delete(String id) {
        noteRepository.delete(id);
    }

    // ================================================================= read

    public NoteMeta require(String id) {
        NoteMeta meta = noteRepository.getNoteMeta(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        return meta;
    }

    public Dtos.NoteDetail detail(String id) {
        NoteMeta meta = require(id);
        String content = noteRepository.text(meta);
        List<String> cardIds = noteRepository.cards(id).stream().map(Card.CardMeta::getId).toList();
        return new Dtos.NoteDetail(meta.getId(), meta.getTitle(), content, meta.getType(),
                                   cardIds, notebookRepository.notebooksOf(id), meta.getCreatedAt(), meta.getUpdatedAt());
    }

    // ================================================================= list

    public record Query(String notebookId, String type, String text, int limit, int offset) {
        public static Query defaults() {
            return new Query(null, null, null, 500, 0);
        }
    }

    /**
     * 列表查询。过滤、排序直接在笔记 meta 上做，返回的就是 meta 本身：meta 是为列表准备的
     * 轻量数据，一次目录遍历即可全部读出（NoteRepository 无状态无缓存，读到的一定是最新文件），
     * 摘要、标签、别名、归属、卡片清单与计数都是现成字段；唯一的例外是文字过滤按需读那篇的
     * 卡片正文。
     */
    public List<NoteMeta> list(Query q) {
        Query query = q == null ? Query.defaults() : q;

        List<NoteMeta> matched = new ArrayList<>();
        for (NoteMeta noteMeta : noteRepository.findAll()) {
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
            // 列表内的文字过滤要真正文 —— 按需加载这篇笔记的卡片，比"为了列表把全库读进内存"划算。
            String body = noteRepository.text(meta).toLowerCase(Locale.ROOT);
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

    public static class ConflictException
            extends com.thisnode.plugin.api.ConflictException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
