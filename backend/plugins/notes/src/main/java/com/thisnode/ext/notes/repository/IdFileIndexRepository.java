package com.thisnode.ext.notes.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 稳定 ID → 物理文件名的索引：一份 JSON 的读改写。
 *
 * <p>子类只提供索引文件名与对象称呼（笔记 / 卡片），读写逻辑都在这里。
 */
@Slf4j
@RequiredArgsConstructor
public abstract class IdFileIndexRepository {

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;


    /** 索引文件名，例如 {@code .card-file-index.json}。 */
    protected abstract String indexFileName();

    /** 索引对象的中文称呼，用于错误信息。 */
    protected abstract String kind();

    public synchronized String getOne(String id) {
        return read().get(id);
    }

    public synchronized Map<String, String> getAll() {
        return new LinkedHashMap<>(read());
    }

    public synchronized void add(String id,
                                 String fileName) {
        Map<String, String> index = read();
        putNew(index, id, fileName);
        write(index);
    }

    public synchronized void delete(String id) {
        Map<String, String> index = read();
        index.remove(id);
        write(index);
    }

    public synchronized void update(String id,
                                    String fileName) {
        Map<String, String> index = read();
        if (!index.containsKey(id)) {
            throw new IllegalStateException(kind() + " ID 不在索引中：" + id);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(kind() + "文件名不能为空");
        }
        index.put(id, fileName);
        write(index);
    }

    @SneakyThrows
    private Map<String, String> read() {
        Path indexFile = notesConfig.getRootDir().resolve(indexFileName());
        try {
            String json = Files.readString(indexFile, StandardCharsets.UTF_8);
            if (json.isBlank()) return new LinkedHashMap<>();
            Map<String, String> index = JSON.parseObject(json, new TypeReference<LinkedHashMap<String, String>>() { });
            return index == null ? new LinkedHashMap<>() : index;
        } catch (IOException | RuntimeException e) {
            log.error("{} ID-文件索引无法解析：{}", kind(), indexFile, e);
            throw e;
        }
    }

    private void write(Map<String, String> index) {
        fileHelper.fileWritten(notesConfig.getRootDir().resolve(indexFileName()), index);
    }

    private void putNew(Map<String, String> target,
                        String id,
                        String fileName) {
        if (id == null || id.isBlank() || fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(kind() + " ID/文件名不能为空");
        }
        String previous = target.putIfAbsent(id, fileName);
        if (previous != null && !previous.equals(fileName)) {
            throw new IllegalStateException(kind() + " ID 重复：" + id);
        }
    }

    /**
     * Persistent direct lookup from stable note IDs to title-based physical files.
     */
    @Repository
    public static class NoteFileIndexRepository
            extends IdFileIndexRepository {

        public NoteFileIndexRepository(NotesConfig notesConfig,
                                       FileHelper fileHelper) {
            super(notesConfig, fileHelper);
        }

        @Override
        protected String indexFileName() {
            return ".note-file-index.json";
        }

        @Override
        protected String kind() {
            return "笔记";
        }
    }

    /**
     * Persistent direct lookup from stable card IDs to title-based physical files.
     */
    @Repository
    public static class CardFileIndexRepository
            extends IdFileIndexRepository {

        public CardFileIndexRepository(NotesConfig notesConfig,
                                       FileHelper fileHelper) {
            super(notesConfig, fileHelper);
        }

        @Override
        protected String indexFileName() {
            return ".card-file-index.json";
        }

        @Override
        protected String kind() {
            return "卡片";
        }
    }
}
