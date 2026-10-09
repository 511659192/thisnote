package com.thisnode.ext.notes.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.domain.*;
import com.thisnode.ext.notes.store.CardRef;
import com.thisnode.ext.notes.store.CardMeta;
import com.thisnode.ext.notes.store.NoteMeta;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.util.Ids;
import com.thisnode.ext.notes.util.Markdown;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * Note aggregate: CRUD, list queries, tags, link re-indexing and [[ ]] resolution.
 *
 * <p>Everything the user writes flows through here so that three invariants hold: links are always
 * a projection of Markdown, the title/alias resolver is never stale, and every write lands in
 * {@code ops}.
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
    private final OpsRepository opsRepository;
    private final ObjectMapper json = new ObjectMapper();

    // ================================================================= create

    public String create(Dtos.CreateNodeRequest req) {
        Note n = new Note();
        n.title = req.title() == null ? "" : req.title().trim();
        n.contentMd = req.contentMd() == null ? "" : req.contentMd();
        n.type = req.type() == null || req.type().isBlank() ? "note" : req.type();
        n.dailyDate = req.dailyDate();
        n.source = req.source();
        n.status = req.status();
        if (n.title.isBlank() && n.contentMd.isBlank()) {
            n.contentMd = "";
        }
        n.id = noteRepository.insert(n);
        // 编排：首卡落盘（一篇笔记至少一张卡；正文只有一份，在卡片里）。
        cardRepository.putCard(n.id, CardMeta.fresh(Ids.newId(), 0, 0, "", null, null,
                System.currentTimeMillis()), n.contentMd == null ? "" : n.contentMd);

        if (req.tags() != null) {
            tagRepository.setNodeTags(n.id, req.tags());
        } else {
            // Inline #tags in the body are picked up automatically.
            List<String> inline = Markdown.inlineTags(n.contentMd);
            if (!inline.isEmpty()) {
                tagRepository.setNodeTags(n.id, inline);
            }
        }
        // 笔记必须归属一个分组：前端在某个分组里新建时会带上当前分组，没带就落默认分组。
        // 不变量在后端收口，客户端（含脚本、导入）不可能造出无归属的笔记。
        String groupId = req.notebookId() != null && !req.notebookId().isBlank() ? req.notebookId() : notebookRepository.ensureDefaultGroup();
        notebookRepository.addNodeToNotebook(n.id, groupId);
        reindexLinks(n);
        resolvePendingLinks(n);
        // 列表页缓存（摘要/出链/入链）随首卡落定：原来由组合层 writeCard 顺带刷新。
        noteRepository.refreshCachedView(n.id);
        log("note", n.id, "create", Map.of("title", n.title));
        return n.id;
    }

    // ================================================================= update

    public Note patch(String id, Dtos.UpdateNodeRequest req) {
        Note n = noteRepository.findById(id);
        if (n == null) {
            throw new NotFoundException("node " + id);
        }
        boolean contentChanged = false;
        String oldTitle = n.title;
        boolean renamed = req.title() != null && !req.title().equals(n.title);
        // 引用者必须在标题改动**之前**收集：链接图是派生的，标题一改，正文里的 [[旧标题]] 当场
        // 就不再指向本篇，之后再问"谁引用了我"只会得到空集。（数据库时代 linkRepository.to_node_id 是
        // 存下来的，改名不会让它变，所以那时先改名后收集也成立 —— 派生模型下不成立。）
        List<String> referrers = renamed ? linkRepository.referencingNodeIds(n.id) : List.of();
        if (renamed) {
            n.title = req.title();
            linkRepository.renameStub(oldTitle, n.title);
            contentChanged = true;
        }
        if (req.contentMd() != null && !req.contentMd().equals(n.contentMd)) {
            n.contentMd = req.contentMd();
            contentChanged = true;
        }
        if (req.source() != null) {
            n.source = req.source();
        }
        if (req.status() != null) {
            n.status = req.status();
        }
        noteRepository.update(n);

        if (req.tags() != null) {
            tagRepository.setNodeTags(id, req.tags());
        } else if (contentChanged) {
            List<String> inline = Markdown.inlineTags(n.contentMd);
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
            // 编排：整篇正文替换（写回首卡）→ 重算链接 → 刷新列表页缓存。
            noteRepository.replaceNoteContent(id, n.contentMd);
            reindexLinks(n);
            noteRepository.refreshCachedView(n.id);
        }
        if (renamed) {
            // Follow the rename through the note directory (like Obsidian) instead of leaving every
            // inbound [[old title]] dangling.
            renameLinksTo(n, oldTitle, n.title, referrers);
            linkRepository.unresolveByTitle(oldTitle);
            invalidateResolver();
            resolvePendingLinks(n);
        }
        log("note", id, "update", Map.of("title", n.title, "contentChanged", contentChanged));
        return n;
    }

    public List<String> tagsOfNames(String noteId) {
        NoteMeta meta = noteRepository.note(noteId);
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
    public void purge(String id) {
        noteRepository.deleteHard(id);
        linkRepository.deleteOutbound(id);
        log("note", id, "purge", Map.of());
    }

    // ================================================================= read

    public Note require(String id) {
        Note n = noteRepository.findById(id);
        if (n == null) {
            throw new NotFoundException("node " + id);
        }
        return n;
    }

    public Dtos.NoteDetail detail(String id) {
        Note n = require(id);
        List<Dtos.LinkDto> outbound = new ArrayList<>();
        for (LinkRepository.LinkRow row : linkRepository.outbound(id)) {
            outbound.add(new Dtos.LinkDto(row.id(), row.fromNodeId(), row.toNodeId(), row.toTitle(), row.label(), row.type(), row.toNodeId() != null));
        }
        List<Dtos.BacklinkDto> backlinks = enrichBacklinks(linkRepository.backlinks(id));
        List<String> unresolved = unresolvedOf(n);
        NoteMeta detailMeta = noteRepository.note(id);
        List<CardRef> detailCards = detailMeta == null ? List.of() : detailMeta.cards();
        return new Dtos.NoteDetail(n.id, n.title, n.contentMd, n.type, n.source, n.status, n.deleted, n.dailyDate, detailCards, tagsOfNames(id), notebookRepository.notebooksOf(id), outbound, backlinks, unresolved, unlinkedMentions(n), n.createdAt, n.updatedAt);
    }

    private List<Dtos.BacklinkDto> enrichBacklinks(List<Dtos.BacklinkDto> raw) {
        List<Dtos.BacklinkDto> out = new ArrayList<>();
        for (Dtos.BacklinkDto b : raw) {
            Note from = noteRepository.findById(b.noteId());
            out.add(new Dtos.BacklinkDto(b.noteId(), from == null ? b.title() : from.title, b.label(), b.heading(), b.context(), b.updatedAt()));
        }
        return out;
    }

    /**
     * Links in the body that currently resolve to nothing (F-L-01 unresolved stub).
     */
    public List<String> unresolvedOf(Note n) {
        List<String> out = new ArrayList<>();
        for (String target : Markdown.unresolvedLinks(n.contentMd)) {
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
    public List<String> unlinkedMentions(Note n) {
        List<String> out = new ArrayList<>();
        String body = n.contentMd == null ? "" : n.contentMd;
        if (body.isBlank()) {
            return out;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        List<String> linkedTitles = new ArrayList<>();
        for (Markdown.WikiLink l : Markdown.wikiLinks(body)) {
            linkedTitles.add(Markdown.normalizeTitle(l.target()));
        }
        List<Note> others = new ArrayList<>();
        for (Note other : noteRepository.findAllActive()) {
            if (!other.id.equals(n.id) && other.title != null && other.title.length() >= 2) {
                others.add(other);
            }
        }
        others.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        int scanned = 0;
        for (Note other : others) {
            if (scanned++ >= 400) {
                break;
            }
            if (lower.contains(other.title.toLowerCase(Locale.ROOT)) && !linkedTitles.contains(Markdown.normalizeTitle(other.title))) {
                out.add(other.title);
                if (out.size() >= 8) {
                    break;
                }
            }
        }
        return out;
    }

    // ================================================================= list

    public record Query(String filter, String tag, String notebookId, String type, String text,
                        boolean includeDeleted, int limit, int offset) {
        public static Query defaults() {
            return new Query("all", null, null, null, null, false, 500, 0);
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
        String filter = query.filter() == null ? "all" : query.filter();

        List<NoteMeta> matched = new ArrayList<>();
        for (NoteMeta meta : noteRepository.scanNotes()) {
            if (matches(meta, query, filter)) {
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
    private boolean matches(NoteMeta meta, Query query, String filter) {
        switch (filter) {
            case "trash" -> {
                if (!meta.deleted()) {
                    return false;
                }
            }
            case "daily" -> {
                if (meta.deleted() || !"daily".equals(meta.type())) {
                    return false;
                }
            }
            default -> {
                if (meta.deleted()) {
                    return false;
                }
            }
        }
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

    /**
     * Re-parse the body and rewrite this node's outbound links + stubs.
     */
    public int reindexLinks(Note n) {
        List<Markdown.WikiLink> parsed = Markdown.wikiLinks(n.contentMd);
        int count = linkRepository.replaceOutbound(n.id, parsed, this::resolveTitle);
        for (Markdown.WikiLink link : parsed) {
            if (resolveTitle(link.target()) == null) {
                linkRepository.ensureStub(link.target());
            } else {
                linkRepository.deleteStubByTitle(link.target());
            }
        }
        invalidateResolver();
        return count;
    }

    /**
     * Full re-index of every node's links (repair / post-import).
     */
    public int reindexAllLinks() {
        // 维护操作：这里本来就要看全库正文，所以显式加载一次（而不是让调用点各自触发）。
        int total = 0;
        List<Note> active = noteRepository.findAllActive();
        for (Note n : active) {
            total += reindexLinks(withContent(n));
        }
        for (Note n : active) {
            resolvePendingLinks(withContent(n));
        }
        // 兜底/修复：先按正文把各篇的缓存重算一遍，再按缓存把入链数全量校准。
        noteRepository.refreshAllCachedViews();
        int fixed = noteRepository.recalibrateInbound();
        if (fixed > 0) {
            log("system", null, "inbound-recalibrated", Map.of("fixed", fixed));
        }
        return total;
    }

    /**
     * 给轻量视图的 Note 补上正文（会按需加载它所属的卡片）。
     */
    private Note withContent(Note n) {
        n.contentMd = noteRepository.noteMarkdown(n.id);
        return n;
    }

    /**
     * Heal unresolved links that now point at this node (or one of its aliases).
     *
     * <p>Supports the natural note-taking order: write {@code [[某概念]]} first, create the
     * note later. Without this pass the link would stay unresolved even though the target
     * exists.
     */
    public int resolvePendingLinks(Note target) {
        List<String> keys = new ArrayList<>();
        keys.add(target.title);
        Set<String> normalized = new HashSet<>();
        for (String key : keys) {
            if (key != null && !key.isBlank()) {
                normalized.add(Markdown.normalizeTitle(key));
                linkRepository.deleteStubByTitle(key);
            }
        }
        // 只动 meta 里缓存的出链：把指向本文的出链补上解析结果，并给本文 +1 入链。
        int healed = noteRepository.resolvePendingLinks(target.id, normalized);
        if (healed > 0) {
            invalidateResolver();
            log("note", target.id, "links-resolved", Map.of("count", healed));
        }
        return healed;
    }

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
    public int renameLinksTo(Note node, String oldTitle, String newTitle, List<String> sourceIdsIn) {
        if (oldTitle == null || newTitle == null || oldTitle.equals(newTitle)) {
            return 0;
        }
        List<String> variants = List.of("[[" + oldTitle + "]]", "[[ " + oldTitle + " ]]", "[[" + oldTitle + "|");
        List<String> replacements = List.of("[[" + newTitle + "]]", "[[ " + newTitle + " ]]", "[[ " + newTitle + "|");
        List<String> sourceIds = sourceIdsIn == null ? List.of() : sourceIdsIn;
        int updated = 0;
        for (String sourceId : sourceIds) {
            boolean changed = false;
            for (Dtos.CardDto block : cardRepository.cards(sourceId)) {
                String body = block.contentMd();
                if (body == null || body.isBlank()) {
                    continue;
                }
                String rewritten = body;
                for (int i = 0; i < variants.size(); i++) {
                    rewritten = rewritten.replace(variants.get(i), replacements.get(i));
                }
                if (!rewritten.equals(body)) {
                    cardRepository.setContent(block.id(), rewritten);
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            Note source = noteRepository.findById(sourceId);
            if (source != null) {
                reindexLinks(source);
            }
            log("note", sourceId, "link-renamed", Map.of("from", oldTitle, "to", newTitle));
            updated++;
        }
        return updated;
    }

    /**
     * Create the missing note behind an unresolved link and link it up.
     */
    public String createFromStub(String title, String fromNodeId) {
        String clean = title == null ? "" : title.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String existing = resolveTitle(clean);
        if (existing != null) {
            return existing;
        }
        String id = create(new Dtos.CreateNodeRequest(clean, "# " + clean + "\n\n", "note", null, null, null, null, null));
        linkRepository.deleteStubByTitle(clean);
        if (fromNodeId != null && !fromNodeId.isBlank()) {
            Note from = noteRepository.findById(fromNodeId);
            if (from != null) {
                reindexLinks(from);
            }
        }
        return id;
    }

    // ================================================================= ops log

    public void log(String entityType, String entityId, String action, Map<String, Object> payload) {
        try {
            opsRepository.append(entityType, entityId, action, json.writeValueAsString(payload));
        } catch (Exception e) {
            // Never fail a user write because the audit row could not be serialized.
        }
    }


    /**
     * Unresolved {@code [[ ]]} targets currently awaiting a note (stub list).
     */
    public List<Map<String, String>> stubs() {
        return linkRepository.listStubs();
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
