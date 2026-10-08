package com.thisnode.ext.notes.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 一篇笔记的元数据：它叫什么、什么类型、归哪个分组、在不在回收站，以及正文之外的标签与别名。
 *
 * <p><b>v2 的关键变化</b>：笔记持有**卡片清单**（{@code cards}），归属方向从「卡片记 noteId」
 * 翻转为「笔记记卡片」。于是加载只需要读每个笔记的 meta（O(笔记数)），卡片正文按需读 ——
 * 上万张卡片时启动/重扫不再是"读一万个文件"。
 *
 * <p><b>列表页缓存</b>：{@code summary}、{@code links}（出链清单）、{@code outboundCount}、
 * {@code inboundCount}、{@code unresolvedCount} 都是写入时算好的派生缓存 —— 列表页因此一个数字
 * 都不用读正文。其中 {@code inboundCount} 是跨笔记的：对端写入时按「旧清单 vs 新清单」的差集
 * 在这里做 ±1。它们都是可重建的缓存，不是第二份权威：丢了只影响列表显示，不影响任何数据。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NoteMeta(
        int v,
        String id,
        String title,
        String type,
        String dailyDate,
        String source,
        String status,
        boolean deleted,
        Long deletedAt,
        List<String> tags,
        List<String> aliases,
        /** 归属分组（单归属）。 */
        @JsonProperty("notebookId") String notebookId,
        /** 卡片清单：**顺序即阅读顺序**（原来靠卡片里的 z 排序，现在由清单位置决定）。 */
        List<CardRef> cards,
        /** 列表页摘要（正文的纯文本前若干字符）。 */
        String summary,
        /** 出链清单（写时算好）：目标原文 + 显示文字 + 解析到的笔记 id。 */
        List<LinkRef> links,
        /** 出链数量（写入时算好，避免列表页为它读正文）。 */
        int outboundCount,
        /** 入链数量（别人引用我）：由对端写入时维护，列表页直接用。 */
        int inboundCount,
        /** 未解析的 {@code [[链接]]} 数量。 */
        int unresolvedCount,
        long createdAt,
        long updatedAt) {

    public static final int SCHEMA = 2;

    public NoteMeta {
        v = v <= 0 ? SCHEMA : v;
        title = title == null ? "" : title;
        type = type == null || type.isBlank() ? "note" : type;
        tags = tags == null ? List.of() : List.copyOf(tags);
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
        cards = cards == null ? List.of() : List.copyOf(cards);
        summary = summary == null ? "" : summary;
        links = links == null ? List.of() : List.copyOf(links);
    }

    public static NoteMeta fresh(String id, String title, String type, String dailyDate,
                                 String source, String status, String notebookId, long now) {
        return new NoteMeta(SCHEMA, id, title, type, dailyDate, source, status, false, null,
                List.of(), List.of(), notebookId, List.of(), "", List.of(), 0, 0, 0, now, now);
    }

    public NoteMeta touched(long now) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, tags, aliases, notebookId, cards, summary, links, outboundCount,
                inboundCount, unresolvedCount, createdAt, now);
    }

    public NoteMeta withTitle(String next) {
        return new NoteMeta(v, id, next == null ? "" : next, type, dailyDate, source, status,
                deleted, deletedAt, tags, aliases, notebookId, cards, summary, links,
                outboundCount, inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    public NoteMeta withType(String next) {
        return new NoteMeta(v, id, title, next, dailyDate, source, status,
                deleted, deletedAt, tags, aliases, notebookId, cards, summary, links,
                outboundCount, inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    public NoteMeta withDailyDate(String next) {
        return new NoteMeta(v, id, title, type, next, source, status,
                deleted, deletedAt, tags, aliases, notebookId, cards, summary, links,
                outboundCount, inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    /** 回收站状态：软删除只改这里，卡片文件原地保留，恢复就是把它翻回来。 */
    public NoteMeta withDeleted(boolean next) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, next,
                next ? (deletedAt == null ? updatedAt : deletedAt) : null,
                tags, aliases, notebookId, cards, summary, links, outboundCount, inboundCount,
                unresolvedCount, createdAt, updatedAt);
    }

    public NoteMeta withSourceStatus(String nsource, String nstatus) {
        return new NoteMeta(v, id, title, type, dailyDate,
                nsource == null ? source : nsource,
                nstatus == null ? status : nstatus,
                deleted, deletedAt, tags, aliases, notebookId, cards, summary, links,
                outboundCount, inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    /** 单归属：笔记必须属于一个分组，所以这里是赋值而不是增删。 */
    public NoteMeta withNotebook(String notebookId) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, tags, aliases, notebookId, cards, summary, links, outboundCount,
                inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    public NoteMeta withTags(List<String> next) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, next == null ? List.of() : List.copyOf(next), aliases, notebookId,
                cards, summary, links, outboundCount, inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    public NoteMeta withAliases(List<String> next) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, tags, next == null ? List.of() : List.copyOf(next), notebookId,
                cards, summary, links, outboundCount, inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    /** 替换卡片清单（顺序即阅读顺序）。 */
    public NoteMeta withCards(List<CardRef> next) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, tags, aliases, notebookId,
                next == null ? List.of() : List.copyOf(next), summary, links, outboundCount,
                inboundCount, unresolvedCount, createdAt, updatedAt);
    }

    /** 更新列表页缓存（摘要 + 出链清单/计数 + 入链数 + 未解析数）。 */
    public NoteMeta withCachedView(String nextSummary, List<LinkRef> nextLinks, int nextOutbound,
                                   int nextInbound, int nextUnresolved) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, tags, aliases, notebookId, cards,
                nextSummary == null ? "" : nextSummary,
                nextLinks == null ? List.of() : List.copyOf(nextLinks),
                nextOutbound, nextInbound, nextUnresolved, createdAt, updatedAt);
    }

    /** 只改入链数（对端写入时用它做 ±1）。 */
    public NoteMeta withInboundCount(int next) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, tags, aliases, notebookId, cards, summary, links, outboundCount,
                Math.max(0, next), unresolvedCount, createdAt, updatedAt);
    }

    /** 替换出链清单（"先写链接、后建笔记"时把 {@code to} 补上）。 */
    public NoteMeta withLinks(List<LinkRef> next) {
        return new NoteMeta(v, id, title, type, dailyDate, source, status, deleted,
                deletedAt, tags, aliases, notebookId, cards, summary,
                next == null ? List.of() : List.copyOf(next), outboundCount, inboundCount,
                unresolvedCount, createdAt, updatedAt);
    }

    /** 与传入的标签名集合合并（正文里的 #tag 自动入账用）。 */
    public NoteMeta mergeTags(List<String> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        List<String> merged = new ArrayList<>(tags);
        for (String t : extra) {
            if (t != null && !t.isBlank()) {
                merged.add(t);
            }
        }
        return withTags(merged);
    }
}
