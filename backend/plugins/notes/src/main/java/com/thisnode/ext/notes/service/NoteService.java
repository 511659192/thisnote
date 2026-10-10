package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.repository.*;
import com.thisnode.ext.notes.domain.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.util.Ids;
import com.thisnode.ext.notes.util.Markdown;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Note aggregate: CRUD, list queries, tags, link re-indexing and [[ ]] resolution.
 *
 * <p>Everything the user writes flows through here so that two invariants hold: links are always
 * a projection of Markdown, and the title/alias resolver is never stale.
 *
 * <p>与数据库时代相比少了一条不变量：不再有"同步镜像"这一步。内容与元数据本来就落在笔记目录里
 * （卡片 md 承载正文、meta 承载归属与几何），文件就是权威，没有第二份需要追平。
 */
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteRepository noteRepository;
    private final TagRepository tagRepository;
    private final LinkRepository linkRepository;
    private final NotebookRepository notebookRepository;
    private final CardRepository cardRepository;

    // ================================================================= create

    public String create(Dtos.CreateNodeRequest req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.contentMd() == null ? "" : req.contentMd();
        String type = req.type() == null || req.type().isBlank() ? "note" : req.type();
        String id = noteRepository.insert(titleOf(title, content), type);
        // 编排：首卡落盘（一篇笔记至少一张卡；正文只有一份，在卡片里）。
        cardRepository.putCard(id, CardMeta.newCardMeta(Ids.newId(), 0, 0, "", null, null, System.currentTimeMillis()), content);

        if (req.tags() != null) {
            tagRepository.setNodeTags(id, req.tags());
        } else {
            // Inline #tags in the body are picked up automatically.
            List<String> inline = Markdown.inlineTags(content);
            if (!inline.isEmpty()) {
                tagRepository.setNodeTags(id, inline);
            }
        }
        // 笔记必须归属一个分组：界面侧在某个分组里新建时会带上当前分组，没带就落默认分组。
        // 不变量在后端收口，客户端（含脚本、导入）不可能造出无归属的笔记。
        String groupId = req.notebookId() != null && !req.notebookId().isBlank() ? req.notebookId() : notebookRepository.ensureDefaultGroup();
        notebookRepository.addNodeToNotebook(id, groupId);
        return id;
    }

    /**
     * 标题兜底：没写标题就用正文首个标题，再没有才叫"无标题笔记"。
     */
    private static String titleOf(String title, String contentMd) {
        if (title != null && !title.isBlank()) {
            return title;
        }
        String fromHeading = Markdown.firstHeading(contentMd);
        return fromHeading != null ? fromHeading : "无标题笔记";
    }

    // ================================================================= update

    public void update(String id, Dtos.UpdateNodeRequest req) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        String content = noteRepository.noteMarkdown(id);
        boolean contentChanged = false;
        String oldTitle = meta.title();
        boolean renamed = req.title() != null && !req.title().equals(oldTitle);
        // 引用者必须在标题改动**之前**收集：链接图是派生的，标题一改，正文里的 [[旧标题]] 当场
        // 就不再指向本篇，之后再问"谁引用了我"只会得到空集。（数据库时代 linkRepository.to_node_id 是
        // 存下来的，改名不会让它变，所以那时先改名后收集也成立 —— 派生模型下不成立。）
        List<String> referrers = renamed ? linkRepository.referencingNodeIds(id) : List.of();
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
        noteRepository.writeNoteMeta(meta.withTitle(nextTitle).touched(System.currentTimeMillis()));

        if (req.tags() != null) {
            tagRepository.setNodeTags(id, req.tags());
        } else if (contentChanged) {
            List<String> inline = Markdown.inlineTags(nextContent);
            if (!inline.isEmpty()) {
                List<String> merged = new ArrayList<>(tagsOfNames(id));
                for (String t : inline) {
                    if (!merged.contains(t)) {
                        merged.add(t);
                    }
                }
                tagRepository.setNodeTags(id, merged);
            }
        }
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
        if (renamed) {
            // Follow the rename through the note directory (like Obsidian) instead of leaving every
            // inbound [[old title]] dangling.
            renameLinksTo(oldTitle, nextTitle, referrers);
            invalidateResolver();
        }
    }

    public List<String> tagsOfNames(String noteId) {
        NoteMeta meta = noteRepository.getOne(noteId);
        if (meta == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>(meta.tags());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ================================================================= delete

    /**
     * 硬删除：卡片 md、sidecar 与笔记 meta 一起消失（仓库层 removeNote）。
     */
    public void delete(String id) {
        noteRepository.delete(id);
    }

    // ================================================================= read

    public NoteMeta require(String id) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        return meta;
    }

    public Dtos.NoteDetail detail(String id) {
        NoteMeta meta = require(id);
        String content = noteRepository.noteMarkdown(id);
        List<String> unresolved = unresolvedOf(content);
        return new Dtos.NoteDetail(meta.id(), meta.title(), content, meta.type(), meta.cards(), tagsOfNames(id), notebookRepository.notebooksOf(id), unresolved, unlinkedMentions(id, content), meta.createdAt(), meta.updatedAt());
    }

    /**
     * Links in the body that currently resolve to nothing (F-L-01 unresolved stub).
     */
    public List<String> unresolvedOf(String contentMd) {
        List<String> out = new ArrayList<>();
        for (String target : Markdown.unresolvedLinks(contentMd)) {
            if (resolveTitle(target) == null) {
                out.add(target);
            }
        }
        return out;
    }

    /**
     * 未链接提及 (F-L2-04): another node's title appears in this body as plain text
     * without a [[ ]] link.
     */
    public List<String> unlinkedMentions(String id, String contentMd) {
        List<String> out = new ArrayList<>();
        String body = contentMd == null ? "" : contentMd;
        if (body.isBlank()) {
            return out;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        List<String> linkedTitles = new ArrayList<>();
        for (Markdown.WikiLink l : Markdown.wikiLinks(body)) {
            linkedTitles.add(Markdown.normalizeTitle(l.target()));
        }
        List<NoteMeta> others = new ArrayList<>();
        for (NoteMeta other : noteRepository.findAll()) {
            if (!other.id().equals(id) && other.title().length() >= 2) {
                others.add(other);
            }
        }
        others.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));
        int scanned = 0;
        for (NoteMeta other : others) {
            if (scanned++ >= 400) {
                break;
            }
            if (lower.contains(other.title().toLowerCase(Locale.ROOT)) && !linkedTitles.contains(Markdown.normalizeTitle(other.title()))) {
                out.add(other.title());
                if (out.size() >= 8) {
                    break;
                }
            }
        }
        return out;
    }

    // ================================================================= list

    public record Query(String tag, String notebookId, String type, String text, int limit, int offset) {
        public static Query defaults() {
            return new Query(null, null, null, null, 500, 0);
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
        for (NoteMeta meta : noteRepository.findAll()) {
            if (matches(meta, query)) {
                matched.add(meta);
            }
        }
        matched.sort((a, b) -> Long.compare(b.updatedAt(), a.updatedAt()));

        int limit = Math.max(1, Math.min(query.limit() <= 0 ? 500 : query.limit(), 2000));
        int offset = Math.max(0, query.offset());
        List<NoteMeta> page = new ArrayList<>();
        for (int i = offset; i < matched.size() && page.size() < limit; i++) {
            page.add(matched.get(i));
        }
        return page;
    }

    /**
     * 逐篇判断，条件与旧的 WHERE 子句一一对应。
     */
    private boolean matches(NoteMeta meta, Query query) {
        if (query.type() != null && !query.type().isBlank() && !query.type().equals(meta.type())) {
            return false;
        }
        if (query.tag() != null && !query.tag().isBlank()) {
            String want = TagRepository.normalize(query.tag());
            boolean has = false;
            for (String tag : meta.tags()) {
                if (tag.equalsIgnoreCase(want)) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                return false;
            }
        }
        if (query.notebookId() != null && !query.notebookId().isBlank() && !query.notebookId().equals(meta.notebookId())) {
            return false;
        }
        if (query.text() != null && !query.text().isBlank()) {
            String needle = query.text().toLowerCase(Locale.ROOT);
            // 列表内的文字过滤要真正文 —— 按需加载这篇笔记的卡片，比"为了列表把全库读进内存"划算。
            String body = noteRepository.noteMarkdown(meta.id()).toLowerCase(Locale.ROOT);
            boolean hit = (meta.title() != null && meta.title().toLowerCase(Locale.ROOT).contains(needle)) || body.contains(needle);
            if (!hit) {
                return false;
            }
        }
        return true;
    }


    private int unresolvedCount(String content) {
        int n = 0;
        for (String target : Markdown.unresolvedLinks(content)) {
            if (resolveTitle(target) == null) {
                n++;
            }
        }
        return n;
    }

    // ================================================================= links

    // ---------------- title resolution ----------------

    /**
     * 解析 {@code [[标题]]}：委托给索引（{@link NoteRepository#resolveTitle}）。链接图、"重命名跟随"、
     * 搜索的未解析判定都用同一份解析结果，各自缓存一份迟早会分叉。
     */
    public String resolveTitle(String target) {
        return noteRepository.resolveTitle(target);
    }

    private void invalidateResolver() {
        // 索引按版本号自己失效；保留这个方法是为了让写入路径读起来仍然明确"解析结果变了"。
    }

    /**
     * Rewrite {@code [[oldTitle]]} / {@code [[oldTitle|label]]} occurrences across the note directory
     * after a rename, mirroring Obsidian. Without this, renaming a note silently breaks
     * every link into it.
     *
     * <p>改写落在**卡片**上而不是"笔记正文"上：正文是从卡片拼出来的派生值，把替换结果写回
     * 派生值等于没有落点。替换本身是纯文本替换，逐卡执行与整篇执行等价。
     */
    public int renameLinksTo(String oldTitle, String newTitle, List<String> sourceIdsIn) {
        if (oldTitle == null || newTitle == null || oldTitle.equals(newTitle)) {
            return 0;
        }
        List<String> variants = Markdown.wikiLinkVariants(oldTitle);
        List<String> replacements = List.of("[[" + newTitle + "]]", "[[ " + newTitle + " ]]", "[[ " + newTitle + "|");
        List<String> sourceIds = sourceIdsIn == null ? List.of() : sourceIdsIn;
        int updated = 0;
        for (String sourceId : sourceIds) {
            boolean changed = false;
            for (Dtos.CardDto card : cardRepository.cards(sourceId)) {
                String body = card.contentMd();
                if (body == null || body.isBlank()) {
                    continue;
                }
                String rewritten = body;
                for (int i = 0; i < variants.size(); i++) {
                    rewritten = rewritten.replace(variants.get(i), replacements.get(i));
                }
                if (!rewritten.equals(body)) {
                    cardRepository.setContent(card.id(), rewritten);
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            updated++;
        }
        return updated;
    }

    /**
     * Create the missing note behind an unresolved link (标题已存在就直接返回它)。
     */
    public String createFromStub(String title) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String existing = resolveTitle(clean);
        if (existing != null) {
            return existing;
        }
        return create(new Dtos.CreateNodeRequest(clean, "# " + clean + "\n\n", "note", null, null));
    }

    public static class NotFoundException extends com.thisnode.plugin.api.NotFoundException {
        public NotFoundException(String what) {
            super("未找到：" + what);
        }
    }

    public static class ConflictException extends com.thisnode.plugin.api.ConflictException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
