package com.thisnode.ext.notes.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * notebook → book 改名的一次性落盘迁移：meta/*.json 的持久化键 notebookId → bookId，
 * 台账目录 notebook/ → book/（notebooks.json → books.json，type 值 notebook → book）。
 * 幂等：已是新命名的数据不命中任何分支。
 */
@Slf4j
@RequiredArgsConstructor
public class NotebookToBookMigration {

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    public void migrateIfNeeded() {
        migrateLedger();
        migrateNoteMetaKeys();
    }

    private void migrateLedger() {
        Path legacyDir = notesConfig.getRootDir().resolve("notebook");
        if (!Files.exists(legacyDir)) return;
        try {
            Path bookDir = notesConfig.getRootDir().resolve("book");
            Files.move(legacyDir, bookDir);
            Path legacyFile = bookDir.resolve("notebooks.json");
            if (Files.exists(legacyFile)) {
                JSONArray books = JSON.parseArray(Files.readString(legacyFile, StandardCharsets.UTF_8));
                if (books != null) {
                    for (Object item : books) {
                        JSONObject book = (JSONObject) item;
                        if ("notebook".equals(book.getString("type"))) {
                            book.put("type", "book");
                        }
                    }
                }
                fileHelper.fileWritten(bookDir.resolve("books.json"), books);
                fileHelper.fileDeleted(legacyFile);
            }
            log.info("分组台账已迁移：notebook/notebooks.json → book/books.json");
        } catch (IOException e) {
            throw new UncheckedIOException("notebook → book 台账迁移失败", e);
        }
    }

    private void migrateNoteMetaKeys() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(notesConfig.getMetaDir(), "*.json")) {
            for (Path file : files) {
                JSONObject meta = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8));
                if (meta == null || !meta.containsKey("notebookId")) continue;
                meta.put("bookId", meta.remove("notebookId"));
                fileHelper.fileWritten(file, meta);
            }
        } catch (NoSuchFileException e) {
            // meta 目录还不存在：没有可迁移的数据
        } catch (IOException e) {
            throw new UncheckedIOException("meta 迁移失败：notebookId → bookId", e);
        }
    }
}
