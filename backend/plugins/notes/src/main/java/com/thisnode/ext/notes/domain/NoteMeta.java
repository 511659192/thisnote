package com.thisnode.ext.notes.domain;

import java.util.List;

/**
 * 一篇笔记的元数据：它叫什么、什么类型、归哪个分组，以及正文之外的标签与别名。
 *
 * <p><b>v2 的关键变化</b>：笔记持有**卡片清单**（{@code cards}），归属方向从「卡片记 noteId」
 * 翻转为「笔记记卡片」。于是加载只需要读每个笔记的 meta（O(笔记数)），卡片正文按需读 ——
 * 上万张卡片时启动/重扫不再是"读一万个文件"。
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
        long createdAt,
        long updatedAt) {

    public static final int SCHEMA = 2;

    public NoteMeta {
        v = v <= 0 ? SCHEMA : v;
        title = title == null ? "" : title;
        type = type == null || type.isBlank() ? "note" : type;
        tags = tags == null ? List.of() : List.copyOf(tags);
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    public static NoteMeta newNoteMeta(String id, String title, String type, String notebookId, long now) {
        return new NoteMeta(SCHEMA, id, title, type, List.of(), notebookId, List.of(), now, now);
    }

    public NoteMeta touched(long now) {
        return new NoteMeta(v, id, title, type, tags, notebookId, cards, createdAt, now);
    }

    public NoteMeta withTitle(String next) {
        return new NoteMeta(v, id, next == null ? "" : next, type, tags, notebookId, cards, createdAt, updatedAt);
    }

    /** 单归属：笔记必须属于一个分组，所以这里是赋值而不是增删。 */
    public NoteMeta withNotebook(String notebookId) {
        return new NoteMeta(v, id, title, type, tags, notebookId, cards, createdAt, updatedAt);
    }

    public NoteMeta withTags(List<String> next) {
        return new NoteMeta(v, id, title, type,
                next == null ? List.of() : List.copyOf(next), notebookId, cards, createdAt, updatedAt);
    }

    /** 替换卡片清单（顺序即阅读顺序）。 */
    public NoteMeta withCards(List<Card> next) {
        return new NoteMeta(v, id, title, type, tags, notebookId,
                next == null ? List.of() : List.copyOf(next), createdAt, updatedAt);
    }
}
