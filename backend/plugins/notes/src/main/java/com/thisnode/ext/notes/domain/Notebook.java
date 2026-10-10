package com.thisnode.ext.notes.domain;

/**
 * 一个分组（笔记本）。
 *
 * <p>台账 {@link NotebookBook} 里装的是它们的清单；**笔记的归属不在这里** —— 归属写在各自笔记
 * meta 的 {@code notebookId} 里，所以移动一篇笔记只改那一个文件。
 */
public record Notebook(int v, String id, String type, String title, String parentId,
                       String icon, int sortOrder, long createdAt, long updatedAt) {

    public static final int SCHEMA = 1;
    public static final String TYPE_NOTEBOOK = "notebook";

    public Notebook {
        v = v <= 0 ? SCHEMA : v;
        type = type == null || type.isBlank() ? TYPE_NOTEBOOK : type;
        title = title == null ? "" : title;
    }

    public static Notebook fresh(String id, String type, String title, String parentId,
                                 String icon, int sortOrder, long now) {
        return new Notebook(SCHEMA, id, type, title, parentId, icon, sortOrder, now, now);
    }

    /** 只换父级（删除分组时把子分组上提一级用）。 */
    public Notebook withParent(String nparentId) {
        return new Notebook(v, id, type, title, nparentId, icon, sortOrder, createdAt, updatedAt);
    }
}
