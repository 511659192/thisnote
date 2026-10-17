package com.thisnode.ext.notes.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.NotePO;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Persistent direct lookup from stable note IDs to title-based physical files.
 */
@Repository
@RequiredArgsConstructor
public class NoteFileIndex {

    private static final String INDEX_FILE = ".note-file-index.json";

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    public synchronized String noteFileName(String noteId) {
        return read().get(noteId);
    }

    public synchronized Map<String, String> noteFiles() {
        return new LinkedHashMap<>(read());
    }

    public synchronized void addNote(String noteId,
                                     String fileName) {
        change(index -> putNew(index, noteId, fileName, "笔记"));
    }

    public synchronized void removeNote(String noteId) {
        change(index -> index.remove(noteId));
    }

    public synchronized void updateNoteFileName(String noteId,
                                                String fileName) {
        change(index -> {
            if (!index.containsKey(noteId)) {
                throw new IllegalStateException("笔记 ID 不在索引中：" + noteId);
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("笔记文件名不能为空");
            }
            index.put(noteId, fileName);
        });
    }

    private Map<String, String> read() {
        try {
            String json = Files.readString(indexFile(), StandardCharsets.UTF_8);
            if (json.isBlank()) return new LinkedHashMap<>();
            Map<String, String> index = JSON.parseObject(json, new TypeReference<LinkedHashMap<String, String>>() { });
            return index == null ? new LinkedHashMap<>() : index;
        } catch (NoSuchFileException e) {
            Map<String, String> rebuilt = scanExistingFiles();
            persist(rebuilt);
            return rebuilt;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("笔记 ID-文件索引无法解析：" + indexFile(), e);
        }
    }

    /**
     * The only directory scan: build the index once when upgrading a library without one.
     */
    private Map<String, String> scanExistingFiles() {
        Map<String, String> index = new LinkedHashMap<>();
        Path metaDir = notesConfig.getMetaDir();
        if (Files.exists(metaDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(metaDir, "*.json")) {
                for (Path file : files) {
                    NotePO note = NotePO.parse(file);
                    if (note == null || note.getNoteId() == null || note.getNoteId().isBlank()) {
                        throw new IllegalStateException("无法为笔记文件建立 ID 索引：" + file);
                    }
                    putNew(index, note.getNoteId(), file.getFileName().toString(), "笔记");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("扫描笔记元数据以建立 ID 索引失败", e);
            }
        }
        return index;
    }

    private void change(Consumer<Map<String, String>> operation) {
        Map<String, String> index = read();
        operation.accept(index);
        persist(index);
    }

    private void persist(Map<String, String> index) {
        fileHelper.fileWritten(indexFile(), index);
    }

    private Path indexFile() {
        return notesConfig.getRootDir().resolve(INDEX_FILE);
    }

    private static void putNew(Map<String, String> target,
                               String id,
                               String fileName,
                               String kind) {
        if (id == null || id.isBlank() || fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException(kind + " ID/文件名不能为空");
        }
        String previous = target.putIfAbsent(id, fileName);
        if (previous != null && !previous.equals(fileName)) {
            throw new IllegalStateException(kind + " ID 重复：" + id);
        }
    }
}
