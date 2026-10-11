package com.thisnode.ext.notes.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组（笔记本）台账：一个文件装全部分组。
 *
 * <p>分组只有个位到十位数量级，不值得一份文件一个。笔记的归属记录在各自笔记 meta 的
 * {@code notebookId} 字段中。
 */
public class NotebookBook {

    public static final int SCHEMA = 1;
    public static final String DEFAULT_GROUP_ID = "grp-default";
    public static final String DEFAULT_GROUP_TITLE = "收集箱";

    private int v = SCHEMA;
    private List<Notebook> notebooks = List.of();

    public NotebookBook() {
    }

    public NotebookBook(int v, List<Notebook> notebooks) {
        setV(v);
        setNotebooks(notebooks);
    }

    public int getV() {
        return v;
    }

    public void setV(int v) {
        this.v = v <= 0 ? SCHEMA : v;
    }

    public List<Notebook> getNotebooks() {
        return notebooks;
    }

    public void setNotebooks(List<Notebook> notebooks) {
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
