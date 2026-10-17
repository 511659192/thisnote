package com.thisnode.ext.notes.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.CardPO;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Persistent direct lookup from stable card IDs to title-based physical files.
 */
@Repository
@RequiredArgsConstructor
public class CardFileIndex {

    private static final String INDEX_FILE = ".card-file-index.json";

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    public synchronized String cardFileName(String cardId) {
        return read().get(cardId);
    }

    public synchronized Map<String, String> cardFiles() {
        return new LinkedHashMap<>(read());
    }

    public synchronized void addCard(String cardId,
                                     String fileName) {
        change(index -> putNew(index, cardId, fileName, "卡片"));
    }

    public synchronized void removeCard(String cardId) {
        change(index -> index.remove(cardId));
    }

    public synchronized void updateCardFileName(String cardId,
                                                String fileName) {
        change(index -> {
            if (!index.containsKey(cardId)) {
                throw new IllegalStateException("卡片 ID 不在索引中：" + cardId);
            }
            if (fileName == null || fileName.isBlank()) {
                throw new IllegalArgumentException("卡片文件名不能为空");
            }
            index.put(cardId, fileName);
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
            throw new IllegalStateException("卡片 ID-文件索引无法解析：" + indexFile(), e);
        }
    }

    /**
     * The only directory scan: build the index once when upgrading a library without one.
     */
    private Map<String, String> scanExistingFiles() {
        Map<String, String> index = new LinkedHashMap<>();
        Path cardsDir = notesConfig.getCardsDir();
        if (Files.exists(cardsDir)) {
            List<Path> cards = new ArrayList<>();
            try (DirectoryStream<Path> files = Files.newDirectoryStream(cardsDir, "*.md")) {
                for (Path file : files) cards.add(file);
            } catch (IOException e) {
                throw new UncheckedIOException("扫描卡片文件以建立 ID 索引失败", e);
            }
            cards.sort(Comparator.comparing((Path file) -> file.getFileName().toString(), String.CASE_INSENSITIVE_ORDER).thenComparing(file -> file.getFileName().toString()));
            for (Path file : cards) {
                CardPO.CardMeta meta;
                try {
                    meta = CardPO.parseMeta(file);
                } catch (IOException e) {
                    throw new UncheckedIOException("读取卡片头部以建立 ID 索引失败：" + file, e);
                }
                if (meta == null || meta.getCardId() == null || meta.getCardId().isBlank()) continue;
                // Manually copied cards may share an ID; keep one deterministic mapping so Notes can resolve the conflict.
                index.putIfAbsent(meta.getCardId(), file.getFileName().toString());
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
