package com.thisnode.ext.notes.domain;

import java.util.List;

/**
 * 一篇笔记的元数据：标题、类型、标签、归属分组，以及按顺序排列的卡片文件相对路径。
 * 卡片身份只由卡片文件头部的 {@link CardMeta#id()} 定义。
 */
public record NoteMeta(int v, String id, String title, String type, List<String> tags,
                       String notebookId, List<String> cardPath, long createdAt, long updatedAt) {

    public static final int SCHEMA = 2;

    public NoteMeta {
        v = v <= 0 ? SCHEMA : v;
        title = title == null ? "" : title;
        type = type == null || type.isBlank() ? "note" : type;
        tags = tags == null ? List.of() : List.copyOf(tags);
        cardPath = cardPath == null ? List.of() : List.copyOf(cardPath);
    }

    public static NoteMeta newNoteMeta(String id, String title, String type, String notebookId) {
        long now = System.currentTimeMillis();
        return new NoteMeta(SCHEMA, id, title, type, List.of(), notebookId, List.of(), now, now);
    }

    public NoteMeta touched(long now) {
        return new NoteMeta(v, id, title, type, tags, notebookId, cardPath, createdAt, now);
    }

    public NoteMeta withTitle(String next) {
        return new NoteMeta(v, id, next == null ? "" : next, type, tags, notebookId,
                cardPath, createdAt, updatedAt);
    }

    /** 单归属：笔记必须属于一个分组，所以这里是赋值而不是增删。 */
    public NoteMeta withNotebook(String notebookId) {
        return new NoteMeta(v, id, title, type, tags, notebookId, cardPath, createdAt, updatedAt);
    }

    public NoteMeta withTags(List<String> next) {
        return new NoteMeta(v, id, title, type, next == null ? List.of() : List.copyOf(next),
                notebookId, cardPath, createdAt, updatedAt);
    }

    /** 替换卡片路径清单（顺序即阅读顺序）。 */
    public NoteMeta withCardPaths(List<String> next) {
        return new NoteMeta(v, id, title, type, tags, notebookId,
                next == null ? List.of() : List.copyOf(next), createdAt, updatedAt);
    }
}
