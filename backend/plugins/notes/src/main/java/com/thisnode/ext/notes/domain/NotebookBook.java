package com.thisnode.ext.notes.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组（笔记本）台账：一个文件装全部分组。
 *
 * <p>分组只有个位到十位数量级，不值得一份文件一个。**笔记的归属不在这里**——它在各自笔记 meta 的
 * {@code notebookId} 里，所以移动一篇笔记只改那一个文件，不会牵动全库台账。
 */
public record NotebookBook(int v,
                           List<Notebook> notebooks) {

    public static final int SCHEMA = 1;
    public static final String DEFAULT_GROUP_ID = "grp-default";
    public static final String DEFAULT_GROUP_TITLE = "收集箱";

    /** 规范构造器：归一化缺省/非法值（{@code v} 补成当前 SCHEMA、清单不可变）。 */
    public NotebookBook(int v, List<Notebook> notebooks) {
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
}
