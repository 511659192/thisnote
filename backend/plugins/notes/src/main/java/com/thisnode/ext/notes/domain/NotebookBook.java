package com.thisnode.ext.notes.domain;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组（笔记本）台账：一个文件装全部分组。
 *
 * <p>分组只有个位到十位数量级，不值得一份文件一个。笔记的归属记录在各自笔记 meta 的
 * {@code notebookId} 字段中。
 */
@Getter
@Setter
public class NotebookBook {

    public static final int SCHEMA = 1;
    public static final String DEFAULT_GROUP_ID = "grp-default";
    public static final String DEFAULT_GROUP_TITLE = "收集箱";

    private int v = SCHEMA;
    private List<Notebook> notebooks = List.of();

    public NotebookBook(int v, List<Notebook> notebooks) {
        setV(v);
        setNotebooks(notebooks);
    }

    public static NotebookBook empty() {
        return new NotebookBook(SCHEMA, List.of());
    }

    public Notebook find(String id) {
        if (id == null) {
            return null;
        }

        return notebooks.stream().filter(nootbook -> nootbook.id().equals(id)).findFirst().get();
    }

    public NotebookBook with(Notebook notebook) {
        List<Notebook> list = new ArrayList<>();
        boolean replaced = false;
        for (Notebook existing : notebooks) {
            if (existing.id().equals(notebook.id())) {
                list.add(notebook);
                replaced = true;
            } else {
                list.add(existing);
            }
        }
        if (!replaced) {
            list.add(notebook);
        }
        return new NotebookBook(v, list);
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
