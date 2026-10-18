package com.thisnode.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.NotePO;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 笔记存储：全部笔记元数据在一份 {@code plugins/notes/note-metadata.json} 的 map 里
 * （{@code noteId → NotePO}），没有「一篇一个文件」也不再需要 ID → 文件名映射。
 *
 * <p>旧 {@code notes/meta/*.json} 与旧索引结构都不迁移、不读取（按约定不做历史迁移）。
 */
@Slf4j
@Repository
public class NoteRepository {

    private final FileHelper fileHelper;
    private final Path nodeMetadataFile;

    public NoteRepository(NotesConfig notesConfig,
                          FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.nodeMetadataFile = notesConfig.getRootDir().resolve("plugins").resolve("notes").resolve(".note-metadata.json");
    }

    public synchronized String save(NotePO note) {
        String noteId = IdUtil.randomUUID();
        note.setNoteId(noteId).setUpdatedAt(System.currentTimeMillis());

        // 获取既有索引
        Map<String, NotePO> notes = noteIndex();
        notes.put(noteId, note);

        // 替换索引
        fileHelper.fileWritten(nodeMetadataFile, notes);
        return noteId;
    }

    /**
     * 只改标题：更新 map 里的数据（不再重命名文件）。
     */
    public synchronized void update(NotePO note,
                                    String newTitle) {
        note.setTitle(newTitle).setUpdatedAt(System.currentTimeMillis());
        put(note);
    }

    /**
     * 覆写一条笔记元数据（卡片清单变更等）。
     */
    public synchronized void update(NotePO note) {
        if (note == null || StrUtil.isBlank(note.getNoteId())) return;
        note.setUpdatedAt(System.currentTimeMillis());
        put(note);
    }

    /**
     * Delete the note metadata; CardService removes its card files first.
     */
    public synchronized void delete(String noteId) {
        Map<String, NotePO> notes = noteIndex();
        if (notes.remove(noteId) != null) {
            fileHelper.fileWritten(nodeMetadataFile, notes);
        }
    }

    /**
     * 全部笔记，按最近更新倒序。
     */
    public synchronized List<NotePO> getAll() {
        List<NotePO> notes = new ArrayList<>(noteIndex().values());
        notes.sort(Comparator.comparingLong(NotePO::getUpdatedAt).reversed());
        return notes;
    }

    /**
     * Read one note's metadata from the map.
     */
    public synchronized NotePO getOne(String noteId) {
        if (StrUtil.isBlank(noteId)) return null;
        return noteIndex().get(noteId);
    }

    /**
     * 更新索引
     * @param note
     */
    private void put(NotePO note) {
        Map<String, NotePO> notes = noteIndex();
        notes.put(note.getNoteId(), note);

        fileHelper.fileWritten(nodeMetadataFile, notes);
    }

    /**
     * 状态重置：把笔记索引重写为空 map。
     */
    public synchronized void reset() {
        fileHelper.fileWritten(nodeMetadataFile, new LinkedHashMap<>());
    }

    /**
     * 读取 {@code noteId → NotePO} 的 map。
     */
    private Map<String, NotePO> noteIndex() {
        try {
            String json = Files.readString(nodeMetadataFile, StandardCharsets.UTF_8);
            Map<String, NotePO> notes = JSON.parseObject(json, new TypeReference<LinkedHashMap<String, NotePO>>() { });
            return notes == null ? new LinkedHashMap<>() : notes;
        } catch (Exception e) {
            log.error("笔记索引无法解析：{}", nodeMetadataFile, e);
            throw new IllegalStateException("笔记索引无法解析 " + nodeMetadataFile, e);
        }
    }
}
