package com.thisnode.ext.notes.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 笔记索引：noteId → 笔记元数据（标题 / 分组 / 卡片清单 / 更新时间）。
 *
 * <p>笔记元数据原来一篇一个 JSON 文件，现在统一存在这里；不再有 noteId → 文件名的映射，
 * 标题也不再决定文件名。
 */
@Getter
@Setter
@NoArgsConstructor
public class NoteIndex {

    private int v = 1;
    private Map<String, NotePO> notes = new LinkedHashMap<>();
}
