package com.thisnode.ext.notes.repository;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.NoteIndex;
import com.thisnode.ext.notes.model.NotePO;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 笔记存储：全部笔记元数据在一份 {@code plugins/notes/.note-index.json} 的 map 里
 * （{@code noteId → NotePO}），没有「一篇一个文件」也不再需要 ID → 文件名映射。
 *
 * <p>旧 {@code notes/meta/*.json} 不迁移、不读取（按约定不做历史迁移）。
 */
@Slf4j
@Repository
public class NoteRepository {

    private final FileHelper fileHelper;
    private final Path indexFile;

    public NoteRepository(NotesConfig notesConfig,
                          FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        this.indexFile = notesConfig.getNotesPluginDir().resolve(".note-index.json");
    }

    public synchronized String save(NotePO note) {
        String noteId = IdUtil.randomUUID();
        note.setNoteId(noteId).setUpdatedAt(System.currentTimeMillis());
        NoteIndex index = readIndex();
        index.getNotes().put(noteId, note);
        writeIndex(index);
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
        NoteIndex index = readIndex();
        if (index.getNotes().remove(noteId) != null) {
            writeIndex(index);
        }
    }

    /**
     * 全部笔记，按最近更新倒序。
     */
    public synchronized List<NotePO> getAll() {
        List<NotePO> notes = new ArrayList<>(readIndex().getNotes().values());
        notes.sort(Comparator.comparingLong(NotePO::getUpdatedAt).reversed());
        return notes;
    }

    /**
     * Read one note's metadata from the map.
     */
    public synchronized NotePO getOne(String noteId) {
        if (StrUtil.isBlank(noteId)) return null;
        return readIndex().getNotes().get(noteId);
    }

    private void put(NotePO note) {
        NoteIndex index = readIndex();
        index.getNotes().put(note.getNoteId(), note);
        writeIndex(index);
    }

    private NoteIndex readIndex() {
        try {
            String json = Files.readString(indexFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new NoteIndex();
            }
            // 旧格式（noteId → 文件名）没有 notes 字段：按 C 约定不迁移，视为空索引。
            JSONObject root = JSON.parseObject(json);
            if (root == null || !root.containsKey("notes")) {
                return new NoteIndex();
            }
            NoteIndex index = JSON.parseObject(json, NoteIndex.class);
            if (index == null) {
                return new NoteIndex();
            }
            if (index.getNotes() == null) {
                index.setNotes(new LinkedHashMap<>());
            }
            return index;
        } catch (NoSuchFileException e) {
            return new NoteIndex();
        } catch (IOException | RuntimeException e) {
            log.error("笔记索引无法解析：{}", indexFile, e);
            throw new IllegalStateException("笔记索引无法解析 " + indexFile, e);
        }
    }

    private void writeIndex(NoteIndex index) {
        fileHelper.fileWritten(indexFile, index);
    }
}
