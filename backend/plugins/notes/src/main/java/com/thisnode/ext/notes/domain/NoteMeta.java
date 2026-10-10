package com.thisnode.ext.notes.domain;

import java.util.List;

/**
 * 一篇笔记的元数据：它叫什么、什么类型、归哪个分组，以及正文之外的标签与别名。
 *
 * <p><b>v2 的关键变化</b>：笔记持有**卡片清单**（{@code cards}），归属方向从「卡片记 noteId」
 * 翻转为「笔记记卡片」。于是加载只需要读每个笔记的 meta（O(笔记数)），卡片正文按需读 ——
 * 上万张卡片时启动/重扫不再是"读一万个文件"。
 *
 * <p><b>列表页缓存</b>：{@code summary}、{@code outLinks}（出链清单）、{@code inLinks}（入链清单，
 * 对端写入时落过来）、{@code outLinksCount}、{@code inLinksCount}（恒等于 {@code inLinks.size()}）、
 * {@code unresolvedLinksCount} 都是写入时算好的派生缓存 —— 列表与详情因此一个数字都不用现算。
 * 它们都是可重建的缓存，不是第二份权威：丢了只影响列表显示，不影响任何数据。
 */
public record NoteMeta(
        int v,
        String id,
        String title,
        String type,
        List<String> tags,
        /** 归属分组（单归属）。 */
        String notebookId,
        /** 卡片清单：**顺序即阅读顺序**（原来靠卡片里的 z 排序，现在由清单位置决定）。 */
        List<Card> cards,
        /** 列表页摘要（正文的纯文本前若干字符）。 */
        String summary,
        /** 出链清单（写时算好）：{@link Link#raw()} 原文 + 显示文字 + 解析到的对端 id。 */
        List<Link> outLinks,
        /** 入链清单（对端写入时落过来）：谁引用我，详情页的引用面板直接用。 */
        List<Link> inLinks,
        /** 出链数量（写入时算好，避免列表页为它读正文）。 */
        int outLinksCount,
        /** 入链数量（别人引用我）：恒等于 {@code inLinks.size()}，由 withInLinks 维护。 */
        int inLinksCount,
        /** 未解析的 {@code [[链接]]} 数量。 */
        int unresolvedLinksCount,
        long createdAt,
        long updatedAt) {

    public static final int SCHEMA = 2;

    public NoteMeta {
        v = v <= 0 ? SCHEMA : v;
        title = title == null ? "" : title;
        type = type == null || type.isBlank() ? "note" : type;
        tags = tags == null ? List.of() : List.copyOf(tags);
        cards = cards == null ? List.of() : List.copyOf(cards);
        summary = summary == null ? "" : summary;
        outLinks = outLinks == null ? List.of() : List.copyOf(outLinks);
        inLinks = inLinks == null ? List.of() : List.copyOf(inLinks);
    }

    public static NoteMeta newNoteMeta(String id, String title, String type, String notebookId, long now) {
        return new NoteMeta(SCHEMA, id, title, type, List.of(), notebookId, List.of(), "",
                List.of(), List.of(), 0, 0, 0, now, now);
    }

    public NoteMeta touched(long now) {
        return new NoteMeta(v, id, title, type, tags, notebookId, cards,
                summary, outLinks, inLinks, outLinksCount, inLinksCount, unresolvedLinksCount, createdAt, now);
    }

    public NoteMeta withTitle(String next) {
        return new NoteMeta(v, id, next == null ? "" : next, type, tags, notebookId, cards, summary,
                outLinks, inLinks, outLinksCount, inLinksCount, unresolvedLinksCount, createdAt, updatedAt);
    }

    /** 单归属：笔记必须属于一个分组，所以这里是赋值而不是增删。 */
    public NoteMeta withNotebook(String notebookId) {
        return new NoteMeta(v, id, title, type, tags, notebookId, cards,
                summary, outLinks, inLinks, outLinksCount, inLinksCount, unresolvedLinksCount,
                createdAt, updatedAt);
    }

    public NoteMeta withTags(List<String> next) {
        return new NoteMeta(v, id, title, type,
                next == null ? List.of() : List.copyOf(next), notebookId,
                cards, summary, outLinks, inLinks, outLinksCount, inLinksCount,
                unresolvedLinksCount, createdAt, updatedAt);
    }

    /** 替换卡片清单（顺序即阅读顺序）。 */
    public NoteMeta withCards(List<Card> next) {
        return new NoteMeta(v, id, title, type, tags, notebookId,
                next == null ? List.of() : List.copyOf(next), summary, outLinks, inLinks,
                outLinksCount, inLinksCount, unresolvedLinksCount, createdAt, updatedAt);
    }

    /** 更新列表页缓存（摘要 + 出链清单/计数 + 未解析数）。入链不在其列：只有对端能写它。 */
    public NoteMeta withCachedView(String nextSummary, List<Link> nextOutLinks, int nextOutbound,
                                   int nextUnresolved) {
        return new NoteMeta(v, id, title, type, tags, notebookId, cards,
                nextSummary == null ? "" : nextSummary,
                nextOutLinks == null ? List.of() : List.copyOf(nextOutLinks),
                inLinks, nextOutbound, inLinksCount, nextUnresolved, createdAt, updatedAt);
    }

    /** 替换入链清单；入链数跟着变成清单长度（两者永远是同一个事实）。 */
    public NoteMeta withInLinks(List<Link> next) {
        List<Link> list = next == null ? List.of() : List.copyOf(next);
        return new NoteMeta(v, id, title, type, tags, notebookId, cards,
                summary, outLinks, list, outLinksCount, list.size(), unresolvedLinksCount, createdAt, updatedAt);
    }

}
