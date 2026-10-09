package com.thisnode.ext.notes.domain;

import com.thisnode.ext.notes.api.dto.Dtos;
import com.thisnode.ext.notes.store.NoteMeta;
import com.thisnode.ext.notes.store.LinkRef;
import com.thisnode.ext.notes.domain.NoteRepository;
import com.thisnode.ext.notes.util.Markdown;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 链接图：{@code [[标题]]} 从笔记正文投影出来，**没有链接表，也没有 stubs 表**。
 *
 * <p>数据库时代链接是 {@code links} + {@code stubs} 两张表，靠每次写入后 replaceOutbound 维护，
 * 一旦漏调就会与正文不一致。文件模型下它们完全是派生数据：正文在卡片里，链接图随时能重算，
 * 所以这里不落盘、不存储，每次现算（读各篇 meta 里缓存的出链清单）。
 *
 * <p>于是所有"修复悬空链接""给 stub 改名""取消解析"之类的方法都成了空操作：一条链接是否解析，
 * 只取决于目标标题此刻存不存在（{@link NoteRepository#resolveTitle}），不需要任何人去修补记录。
 */
@Repository
public class LinkRepository {

    private final NoteRepository noteRepository;

    public LinkRepository(NoteRepository noteRepository) {
        this.noteRepository = noteRepository;
    }

    public record LinkRow(String id, String fromNodeId, String toNodeId, String toTitle,
                          String label, String type, long createdAt) {
    }

    /** 一张从正文算出来的链接图。 */
    private record LinkGraph(Map<String, List<LinkRow>> outbound,
                             Map<String, List<LinkRow>> inbound,
                             Set<String> stubTitles,
                             Map<String, String> stubDisplay,
                             int total) {
    }

    private LinkGraph graph() {
        Map<String, List<LinkRow>> outbound = new HashMap<>();
        Map<String, List<LinkRow>> inbound = new HashMap<>();
        Set<String> stubs = new LinkedHashSet<>();
        Map<String, String> display = new LinkedHashMap<>();
        int total = 0;
        for (NoteMeta meta : noteRepository.scanNotes()) {
            if (meta.deleted()) {
                continue;
            }
            List<LinkRow> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int seq = 0;
            for (LinkRef link : meta.links()) {
                String key = Markdown.normalizeTitle(link.target()) + "|" + link.label();
                if (!seen.add(key)) {
                    continue;
                }
                LinkRow row = new LinkRow(meta.id() + "#" + (seq++), meta.id(), link.to(),
                        link.target(), link.label(), "wiki", meta.updatedAt());
                rows.add(row);
                total++;
                if (link.to() == null) {
                    String norm = Markdown.normalizeTitle(link.target());
                    if (!norm.isEmpty()) {
                        stubs.add(norm);
                        display.putIfAbsent(norm, link.target());
                    }
                } else {
                    inbound.computeIfAbsent(link.to(), k -> new ArrayList<>()).add(row);
                }
            }
            if (!rows.isEmpty()) {
                outbound.put(meta.id(), rows);
            }
        }
        return new LinkGraph(outbound, inbound, stubs, display, total);
    }

    // -------------------------------------------------------------------- 读

    public List<LinkRow> outbound(String noteId) {
        return graph().outbound().getOrDefault(noteId, List.of());
    }

    public List<LinkRow> inbound(String noteId) {
        return graph().inbound().getOrDefault(noteId, List.of());
    }

    public int countOutbound(String noteId) {
        return outbound(noteId).size();
    }

    public int countInbound(String noteId) {
        return inbound(noteId).size();
    }

    /** 入链数：链接图取一次，逐篇查表（出链数在笔记 meta 里有缓存，不必再问图）。 */
    public int total() {
        return graph().total();
    }

    public Map<String, String> allLinkCounts() {
        Map<String, String> map = new LinkedHashMap<>();
        graph().outbound().forEach((noteId, rows) -> map.put(noteId, String.valueOf(rows.size())));
        return map;
    }

    public List<Map<String, Object>> graphEdges() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<LinkRow>> entry : graph().outbound().entrySet()) {
            for (LinkRow row : entry.getValue()) {
                if (row.toNodeId() != null) {
                    out.add(Map.of("source", entry.getKey(), "target", row.toNodeId(),
                            "type", row.type()));
                }
            }
        }
        return out;
    }

    /** Source nodes whose links point at this node. */
    public List<String> referencingNodeIds(String noteId) {
        List<String> out = new ArrayList<>();
        for (LinkRow row : inbound(noteId)) {
            if (!out.contains(row.fromNodeId())) {
                out.add(row.fromNodeId());
            }
        }
        return out;
    }

    /**
     * 未解析、且目标标题正好落在这批 key 里的链接。
     *
     * <p>注意它在派生模型下的语义：调用方（"新建笔记后修复指向它的悬空链接"）拿到结果时，
     * 链接其实已经因为目标出现而自动解析了 —— 所以这里通常返回空。这不是功能缺失，而是
     * "不需要修复"：正文没变，变的只是它能否被解析。
     */
    public List<LinkRow> unresolvedMatching(Collection<String> targets) {
        Set<String> keys = new HashSet<>();
        for (String target : targets) {
            String key = Markdown.normalizeTitle(target);
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            return List.of();
        }
        List<LinkRow> out = new ArrayList<>();
        for (List<LinkRow> rows : graph().outbound().values()) {
            for (LinkRow row : rows) {
                if (row.toNodeId() == null && keys.contains(Markdown.normalizeTitle(row.toTitle()))) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    // ------------------------------------------- 派生数据下不需要落库的写方法

    /** 链接图从正文重算，没有需要删除的记录。 */
    public void deleteOutbound(String noteId) {
    }

    /** 派生数据，不落库；保留签名以免破坏调用面。 */
    public void insert(String fromNodeId, String toNodeId, String toTitle, String label, String type) {
    }

    /** 报告本次解析出多少条链接（去重后），供 {@code reindexLinks} 返回计数。 */
    public int replaceOutbound(String fromNodeId, List<Markdown.WikiLink> parsed,
                               java.util.function.Function<String, String> resolver) {
        Set<String> seen = new HashSet<>();
        int count = 0;
        for (Markdown.WikiLink link : parsed) {
            if (seen.add(Markdown.normalizeTitle(link.target()) + "|" + link.label())) {
                count++;
            }
        }
        return count;
    }

    /** 解析状态由标题决定，没有需要改写的记录。 */
    public void resolveLink(String linkId, String toNodeId) {
    }

    /** 目标不存在时链接自动变成未解析，无需"取消解析"。 */
    public int unresolveByTitle(String title) {
        return 0;
    }

    public void ensureStub(String title) {
    }

    public void deleteStubByTitle(String title) {
    }

    public void renameStub(String oldTitle, String newTitle) {
    }

    public boolean stubExists(String title) {
        return graph().stubTitles().contains(Markdown.normalizeTitle(title));
    }

    /** 被引用但还不存在的标题（stub 列表），按标题排序。 */
    public List<Map<String, String>> listStubs() {
        List<String> keys = new ArrayList<>(graph().stubTitles());
        keys.sort(String.CASE_INSENSITIVE_ORDER);
        List<Map<String, String>> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            out.add(Map.of("id", key, "title", graph().stubDisplay().getOrDefault(key, key)));
        }
        return out;
    }

    public List<String> stubTitlesIn(String contentMd) {
        return new ArrayList<>(Markdown.unresolvedLinks(contentMd));
    }

}
