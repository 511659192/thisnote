package com.thisnode.ext.notes.domain;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 一篇笔记的元数据：标题、类型、标签、归属分组，以及按顺序排列的卡片文件相对路径。
 * 卡片身份只由卡片文件头部的 {@link CardMeta#id()} 定义。
 */

@Getter
@Setter
@AllArgsConstructor
public class NoteMeta {

    public static final int SCHEMA = 2;

    private int v;
    private String id;
    private String title;
    private String type;
    private List<String> tags;
    private String notebookId;
    private List<String> cardPaths;
    private long createdAt;
    private long updatedAt;

    public NoteMeta() {
        this(SCHEMA, null, "", "note", List.of(), null, List.of(), System.currentTimeMillis(), System.currentTimeMillis());
    }

    public static NoteMeta newNoteMeta(String id, String title, String type, String notebookId) {
        long now = System.currentTimeMillis();
        return new NoteMeta(SCHEMA, id, title, type, List.of(), notebookId, List.of(), now, now);
    }

    public void touch(long now) {
        updatedAt = now;
    }
}
