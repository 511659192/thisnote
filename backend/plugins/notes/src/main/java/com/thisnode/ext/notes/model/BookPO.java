package com.thisnode.ext.notes.model;

import com.alibaba.fastjson2.JSON;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 一个分组（笔记本）。
 *
 * <p>分组元数据由台账文件保存；**笔记的归属不在这里**，而是记录在各自笔记 meta 的
 * {@code notebookId} 字段中。
 */
@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
@Slf4j
public class BookPO {

    public static final int SCHEMA = 1;
    public static final String TYPE_BOOK = "book";
    public static final String DEFAULT_GROUP_ID = "grp-default";
    public static final String DEFAULT_GROUP_TITLE = "收集箱";

    private int v = SCHEMA;
    private String id;
    private String type = TYPE_BOOK;
    private String title = "";
    private String icon;
    private int sortOrder;
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = System.currentTimeMillis();

    public BookPO(int v,
                    String id,
                    String type,
                    String title,
                    String icon,
                    int sortOrder,
                    long createdAt,
                    long updatedAt) {
        this.v = v;
        this.id = id;
        this.type = type;
        this.title = title;
        this.icon = icon;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        normalize();
    }

    private void normalize() {
        if (v <= 0) {
            v = SCHEMA;
        }
        if (type == null || type.isBlank()) {
            type = TYPE_BOOK;
        }
        if (title == null) {
            title = "";
        }
    }

    public static BookPO newBook(String id,
                                       String type,
                                       String title,
                                       String icon,
                                       int sortOrder,
                                       long now) {
        return new BookPO(SCHEMA, id, type, title, icon, sortOrder, now, now);
    }

    public static List<BookPO> parse(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new ArrayList<>();
            }
            List<BookPO> loaded = JSON.parseArray(json, BookPO.class);
            if (loaded == null) {
                return new ArrayList<>();
            }
            for (BookPO book : loaded) {
                if (book != null) {
                    book.normalize();
                }
            }
            return new ArrayList<>(loaded);
        } catch (NoSuchFileException e) {
            return new ArrayList<>();
        } catch (IOException | RuntimeException e) {
            log.warn("分组清单无法读取，按空清单处理：{}", e.toString());
            return new ArrayList<>();
        }
    }
}
