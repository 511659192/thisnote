package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.NoteMeta;
import com.thisnode.plugin.api.util.JsonFileUtils;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Persistent direct lookup from stable entity IDs to title-based physical files. */
@Repository
@RequiredArgsConstructor
public class IdFileIndex {

    private static final String INDEX_FILE = ".id-file-index.json";

    private final NotesConfig notesConfig;

    public synchronized String noteFileName(String noteId) { return read().noteFiles.get(noteId); }
    public synchronized String cardFileName(String cardId) { return read().cardFiles.get(cardId); }
    public synchronized Map<String, String> noteFiles() { return new LinkedHashMap<>(read().noteFiles); }
    public synchronized Map<String, String> cardFiles() { return new LinkedHashMap<>(read().cardFiles); }

    public synchronized void addNote(String noteId, String fileName) {
        change(index -> putNew(index.noteFiles, noteId, fileName, "笔记"));
    }

    public synchronized void addCard(String cardId, String fileName) {
        change(index -> putNew(index.cardFiles, cardId, fileName, "卡片"));
    }

    public synchronized void removeNote(String noteId) { change(index -> index.noteFiles.remove(noteId)); }
    public synchronized void removeCard(String cardId) { change(index -> index.cardFiles.remove(cardId)); }

    public synchronized void updateNoteFileName(String noteId, String fileName) {
        change(index -> {
            if (!index.noteFiles.containsKey(noteId)) {
                throw new IllegalStateException("笔记 ID 不在索引中：" + noteId);
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("笔记文件名不能为空");
            }
            index.noteFiles.put(noteId, fileName);
        });
    }

    public synchronized void updateCardFileName(String cardId, String fileName) {
        change(index -> {
            if (!index.cardFiles.containsKey(cardId)) {
                throw new IllegalStateException("卡片 ID 不在索引中：" + cardId);
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("卡片文件名不能为空");
            }
            index.cardFiles.put(cardId, fileName);
        });
    }

    private IndexData read() {
        Path indexFile = indexPath();
        if (!Files.isRegularFile(indexFile)) {
            IndexData rebuilt = scanExistingFiles();
            persist(rebuilt);
            return rebuilt;
        }
        try {
            IndexData index = JsonFileUtils.read(indexFile, IndexData.class);
            if (index == null) return new IndexData();
            if (index.noteFiles == null) index.noteFiles = new LinkedHashMap<>();
            if (index.cardFiles == null) index.cardFiles = new LinkedHashMap<>();
            return index;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("ID-文件索引无法解析：" + indexFile, e);
        }
    }

    /** The only directory scan: build the index once when upgrading a library without one. */
    private IndexData scanExistingFiles() {
        IndexData index = new IndexData();
        Path metaDir = notesConfig.getNoteMetaDir();
        if (Files.isDirectory(metaDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(metaDir, "*.json")) {
                for (Path file : files) {
                    NoteMeta note = NoteMeta.parse(file);
                    if (note == null || note.getNoteId() == null || note.getNoteId().isBlank()) {
                        throw new IllegalStateException("无法为笔记文件建立 ID 索引：" + file);
                    }
                    putNew(index.noteFiles, note.getNoteId(), file.getFileName().toString(), "笔记");
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("扫描笔记元数据以建立 ID 索引失败", e);
            }
        }

        Path cardsDir = notesConfig.getCardsDir();
        if (Files.isDirectory(cardsDir)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(cardsDir, "*.md")) {
                for (Path file : files) {
                    Card.CardMeta meta;
                    try {
                        meta = Card.parseMeta(file);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException("读取卡片头部以建立 ID 索引失败：" + file, e);
                    }
                    if (meta == null || meta.getCardId() == null || meta.getCardId().isBlank()) continue;
                    putNew(index.cardFiles, meta.getCardId(), file.getFileName().toString(), "卡片");
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("扫描卡片文件以建立 ID 索引失败", e);
            }
        }
        return index;
    }

    private void change(Consumer<IndexData> operation) {
        IndexData index = read();
        operation.accept(index);
        persist(index);
    }

    private void persist(IndexData index) {
        JsonFileUtils.write(indexPath(), index);
    }

    private Path indexPath() {
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

    @Getter
    @Setter
    @NoArgsConstructor
    public static class IndexData {
        private Map<String, String> noteFiles = new LinkedHashMap<>();
        private Map<String, String> cardFiles = new LinkedHashMap<>();
    }
}
