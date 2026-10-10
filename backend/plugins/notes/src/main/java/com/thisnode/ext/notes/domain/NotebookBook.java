package com.thisnode.ext.notes.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组（笔记本）台账：一个文件装全部分组。
 *
 * <p>分组只有个位到十位数量级，不值得一份文件一个。**笔记的归属不在这里**——它在各自笔记 meta 的
 * {@code notebookId} 里，所以移动一篇笔记只改那一个文件，不会牵动全库台账。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotebookBook(int v,
                           @JsonProperty("notebooks") List<Notebook> notebooks) {

    public static final int SCHEMA = 1;
    public static final String DEFAULT_GROUP_ID = "grp-default";
    public static final String DEFAULT_GROUP_TITLE = "收集箱";
    public static final String TYPE_NOTEBOOK = "notebook";

    /** {@code @JsonCreator(PROPERTIES)}：显式声明这个 canonical 构造器就是 JSON 的属性绑定入口。 */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public NotebookBook(int v, @JsonProperty("notebooks") List<Notebook> notebooks) {
        this.v = v <= 0 ? SCHEMA : v;
        this.notebooks = notebooks == null ? List.of() : List.copyOf(notebooks);
    }

    public static NotebookBook empty() {
        return new NotebookBook(SCHEMA, List.of());
    }

    public Notebook find(String id) {
        if (id == null) {
            return null;
        }
        for (Notebook notebook : notebooks) {
            if (notebook.id().equals(id)) {
                return notebook;
            }
        }
        return null;
    }

    public Notebook findByTitle(String type, String title) {
        for (Notebook notebook : notebooks) {
            if (notebook.type().equals(type) && notebook.title().equalsIgnoreCase(title)) {
                return notebook;
            }
        }
        return null;
    }

    public NotebookBook with(Notebook notebook) {
        List<Notebook> next = new ArrayList<>();
        boolean replaced = false;
        for (Notebook existing : notebooks) {
            if (existing.id().equals(notebook.id())) {
                next.add(notebook);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(notebook);
        }
        return new NotebookBook(v, next);
    }

    public NotebookBook without(String id) {
        List<Notebook> next = new ArrayList<>();
        for (Notebook existing : notebooks) {
            if (!existing.id().equals(id)) {
                next.add(existing);
            }
        }
        return new NotebookBook(v, next);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Notebook(int v, String id, String type, String title, String parentId,
                            String icon, int sortOrder, long createdAt, long updatedAt) {

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
}
