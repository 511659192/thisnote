package com.thisnode.ext.notes.domain;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个分组（笔记本）。
 *
 * <p>分组元数据由台账文件保存；**笔记的归属不在这里**，而是记录在各自笔记 meta 的
 * {@code notebookId} 字段中。
 */
@Slf4j
public record Notebook(int v, String id, String type, String title,
                       String icon, int sortOrder, long createdAt, long updatedAt) {

    public static final int SCHEMA = 1;
    public static final String TYPE_NOTEBOOK = "notebook";
    public static final String DEFAULT_GROUP_ID = "grp-default";
    public static final String DEFAULT_GROUP_TITLE = "收集箱";

    public Notebook {
        v = v <= 0 ? SCHEMA : v;
        type = type == null || type.isBlank() ? TYPE_NOTEBOOK : type;
        title = title == null ? "" : title;
    }

    public static Notebook fresh(String id,
                                 String type,
                                 String title,
                                 String icon,
                                 int sortOrder,
                                 long now) {
        return new Notebook(SCHEMA, id, type, title, icon, sortOrder, now, now);
    }


    public static List<Notebook> parse(Path file) {
        if (!Files.isRegularFile(file)) {
            return new ArrayList<>();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            List<Notebook> loaded = JSON.parseArray(json, Notebook.class);
            return loaded == null ? new ArrayList<>() : new ArrayList<>(loaded);
        } catch (IOException | RuntimeException e) {
            log.warn("分组清单无法读取，按空清单处理：{}", e.toString());
            return new ArrayList<>();
        }
    }
}
