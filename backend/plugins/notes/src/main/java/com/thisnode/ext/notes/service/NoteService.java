package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.repository.*;
import com.thisnode.ext.notes.domain.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.ext.notes.domain.Tag;
import cn.hutool.core.util.IdUtil;
import com.thisnode.ext.notes.util.JsonFileUtils;
import com.thisnode.ext.notes.util.MarkdownUtils;
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
    private final NotebookRepository notebookRepository;
    private final CardRepository cardRepository;

    // ================================================================= create

    public String create(Dtos.CreateNodeRequest req) {
        String title = req.title() == null ? "" : req.title().trim();
        String content = req.contentMd() == null ? "" : req.contentMd();
        String type = req.type() == null || req.type().isBlank() ? "note" : req.type();
        String id = noteRepository.insert(titleOf(title, content), type);
        // 编排：首卡落盘（一篇笔记至少一张卡；正文只有一份，在卡片里）。
        cardRepository.putCard(id, CardMeta.newCardMeta(IdUtil.randomUUID(), 0, 0, "", null, null, System.currentTimeMillis()), content);

        if (req.tags() != null) {
            setTags(id, req.tags());
        } else {
            // Inline #tags in the body are picked up automatically.
            List<String> inline = MarkdownUtils.inlineTags(content);
            if (!inline.isEmpty()) {
                setTags(id, inline);
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
        String fromHeading = MarkdownUtils.firstHeading(contentMd);
        return fromHeading != null ? fromHeading : "无标题笔记";
    }

    // ================================================================= update

    /** 扫描正文，找出以标题链接引用该笔记的其他笔记。只在改名时按需计算。 */
    private List<String> referencingNodeIds(String noteId) {
        NoteMeta note = noteRepository.getOne(noteId);
        if (note == null || note.getTitle().isBlank()) {
            return List.of();
        }
        List<String> variants = MarkdownUtils.wikiLinkVariants(note.getTitle());
        List<String> referrers = new ArrayList<>();
        for (NoteMeta other : noteRepository.findAll()) {
            if (other.getId().equals(noteId)) {
                continue;
            }
            String markdown = noteRepository.noteMarkdown(other.getId());
            for (String variant : variants) {
                if (markdown.contains(variant)) {
                    referrers.add(other.getId());
                    break;
                }
            }
        }
        return referrers;
    }

    public void update(String id, Dtos.UpdateNodeRequest req) {
        NoteMeta meta = noteRepository.getOne(id);
        if (meta == null) {
            throw new NotFoundException("node " + id);
        }
        String content = noteRepository.noteMarkdown(id);
        boolean contentChanged = false;
        String oldTitle = meta.getTitle();
        boolean renamed = req.title() != null && !req.title().equals(oldTitle);
        // 引用者必须在标题改动**之前**收集：链接图是派生的，标题一改，正文里的 [[旧标题]] 当场
        // 就不再指向本篇，之后再问"谁引用了我"只会得到空集。引用关系从正文现算，所以必须先收集。
        List<String> referrers = renamed ? referencingNodeIds(id) : List.of();
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
        meta.touch(System.currentTimeMillis());
        JsonFileUtils.write(noteRepository.paths().noteMeta(id), meta);

        if (req.tags() != null) {
            setTags(id, req.tags());
        } else if (contentChanged) {
            List<String> inline = MarkdownUtils.inlineTags(nextContent);
            if (!inline.isEmpty()) {
                List<String> merged = new ArrayList<>(tagsOfNames(id));
                for (String t : inline) {
                    if (!merged.contains(t)) {
                        merged.add(t);
                    }
                }
                setTags(id, merged);
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
        List<String> out = new ArrayList<>(meta.getTags());
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    // ================================================================= tags（归属在 meta，台账在 TagRepository）

    /** 写标签归属：改笔记 meta，并把名字登记进标签台账（两个仓储各管自己的文件）。 */
    private void setTags(String noteId, List<String> names) {
        noteRepository.setNodeTags(noteId, names);
        tagRepository.registerAll(names);
    }

    /** 一篇笔记的标签（名字 + 台账里的颜色）。 */
    public List<Dtos.TagDto> tagsOf(String noteId) {
        List<Dtos.TagDto> out = new ArrayList<>();
        for (String name : noteRepository.tagNames(noteId)) {
            out.add(new Dtos.TagDto(name, name, tagRepository.colorOf(name), null));
        }
        return out;
    }

    /** 加一个标签，返回这篇笔记最新的标签。 */
    public List<Dtos.TagDto> addTag(String noteId, String name) {
        noteRepository.addNodeTag(noteId, name);
        tagRepository.registerAll(List.of(name));
        return tagsOf(noteId);
    }

    public List<Dtos.TagDto> removeTag(String noteId, String name) {
        noteRepository.removeNodeTag(noteId, name);
        return tagsOf(noteId);
    }

    /** 标签清单（侧栏标签云）：台账里的都在（含计数 0 的），外加只在笔记里出现过的；计数现算。 */
    public List<Dtos.TagDto> tagCatalog() {
        List<String> all = noteRepository.allNoteTags();
        Map<String, Integer> counts = new HashMap<>();
        for (String tag : all) {
            counts.merge(tag.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        Set<String> known = new HashSet<>();
        List<Dtos.TagDto> out = new ArrayList<>();
        for (Tag tag : tagRepository.tags()) {
            String key = tag.name().toLowerCase(Locale.ROOT);
            known.add(key);
            out.add(new Dtos.TagDto(tag.name(), tag.name(), tag.color(), counts.getOrDefault(key, 0)));
        }
        for (String name : all) {
            String key = name.toLowerCase(Locale.ROOT);
            if (known.add(key)) {
                out.add(new Dtos.TagDto(name, name, null, counts.getOrDefault(key, 0)));
            }
        }
        out.sort(Comparator.comparingInt(Dtos.TagDto::count).reversed()
                .thenComparing(Dtos.TagDto::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** 改名：笔记 meta 与台账两边一起改（各管自己那一半）。 */
    public void renameTag(String from, String to) {
        String src = Tag.normalizeName(from);
        String dst = Tag.normalizeName(to);
        if (src.isEmpty() || dst.isEmpty() || src.equalsIgnoreCase(dst)) {
            return;
        }
        noteRepository.renameTagInMetas(src, dst);
        tagRepository.rename(src, dst);
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
        List<String> cardIds = noteRepository.cards(id).stream().map(CardMeta::id).toList();
        return new Dtos.NoteDetail(meta.getId(), meta.getTitle(), content, meta.getType(), cardIds, tagsOfNames(id), notebookRepository.notebooksOf(id), unresolved, unlinkedMentions(id, content), meta.getCreatedAt(), meta.getUpdatedAt());
    }

    /**
     * Links in the body that currently resolve to nothing (F-L-01 unresolved stub).
     */
    public List<String> unresolvedOf(String contentMd) {
        List<String> out = new ArrayList<>();
        for (String target : MarkdownUtils.unresolvedLinks(contentMd)) {
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
        for (MarkdownUtils.WikiLink l : MarkdownUtils.wikiLinks(body)) {
            linkedTitles.add(MarkdownUtils.normalizeTitle(l.target()));
        }
        List<NoteMeta> others = new ArrayList<>();
        for (NoteMeta other : noteRepository.findAll()) {
            if (!other.getId().equals(id) && other.getTitle().length() >= 2) {
                others.add(other);
            }
        }
        others.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
        int scanned = 0;
        for (NoteMeta other : others) {
            if (scanned++ >= 400) {
                break;
            }
            if (lower.contains(other.getTitle().toLowerCase(Locale.ROOT)) && !linkedTitles.contains(MarkdownUtils.normalizeTitle(other.getTitle()))) {
                out.add(other.getTitle());
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
        matched.sort((a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));

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
        if (query.type() != null && !query.type().isBlank() && !query.type().equals(meta.getType())) {
            return false;
        }
        if (query.tag() != null && !query.tag().isBlank()) {
            String want = Tag.normalizeName(query.tag());
            boolean has = false;
            for (String tag : meta.getTags()) {
                if (tag.equalsIgnoreCase(want)) {
                    has = true;
                    break;
                }
            }
            if (!has) {
                return false;
            }
        }
        if (query.notebookId() != null && !query.notebookId().isBlank() && !query.notebookId().equals(meta.getNotebookId())) {
            return false;
        }
        if (query.text() != null && !query.text().isBlank()) {
            String needle = query.text().toLowerCase(Locale.ROOT);
            // 列表内的文字过滤要真正文 —— 按需加载这篇笔记的卡片，比"为了列表把全库读进内存"划算。
            String body = noteRepository.noteMarkdown(meta.getId()).toLowerCase(Locale.ROOT);
            boolean hit = (meta.getTitle() != null && meta.getTitle().toLowerCase(Locale.ROOT).contains(needle)) || body.contains(needle);
            if (!hit) {
                return false;
            }
        }
        return true;
    }


    private int unresolvedCount(String content) {
        int n = 0;
        for (String target : MarkdownUtils.unresolvedLinks(content)) {
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
        List<String> variants = MarkdownUtils.wikiLinkVariants(oldTitle);
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
