package com.thisnode.ext.cardimporter.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnode.plugin.api.util.FileHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Service
public class CardImporterService {

    private static final Logger log = LoggerFactory.getLogger(CardImporterService.class);
    private static final long SCAN_INTERVAL_MILLIS = 10_000;
    private static final String META_PREFIX = "<!-- thisnode:meta ";
    private static final String META_SUFFIX = " -->";
    private static final String INDEX_FILE = ".id-file-index.json";
    private static final String DEFAULT_NOTEBOOK_ID = "grp-default";

    private final Path metaDir;
    private final Path cardsDir;
    private final Path indexFile;
    private final FileHelper fileHelper;

    public CardImporterService(FileHelper fileHelper,
                               @Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        Path notesRoot = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes");
        this.metaDir = notesRoot.resolve("meta");
        this.cardsDir = notesRoot.resolve("cards");
        this.indexFile = notesRoot.resolve(INDEX_FILE);
        this.fileHelper = fileHelper;
    }

    @Scheduled(fixedDelay = SCAN_INTERVAL_MILLIS)
    public void scan() {
        try {
            importUnmanagedCards();
        } catch (IOException | RuntimeException e) {
            log.warn("扫描 notes/cards 导入未管理卡片失败", e);
        }
    }

    private void importUnmanagedCards() throws IOException {
        if (!Files.isDirectory(cardsDir)) return;

        List<Path> cardPaths = listCardPaths();
        if (cardPaths.isEmpty()) return;

        List<NoteMetaFile> noteMetas = readNoteMetas();
        Set<String> managedCardIds = new HashSet<>();
        for (NoteMetaFile noteMeta : noteMetas) {
            managedCardIds.addAll(noteMeta.cardIds());
        }

        List<CardFile> cardFiles = new ArrayList<>(cardPaths.size());
        for (Path path : cardPaths) {
            cardFiles.add(readCardFile(path));
        }

        IndexData index = readIndex(noteMetas, cardFiles);
        Map<String, List<CardFile>> cardFilesById = groupByCardId(cardFiles);
        Set<String> reservedNoteIds = new HashSet<>(index.noteFiles.keySet());
        for (NoteMetaFile noteMeta : noteMetas) {
            if (noteMeta.noteId() != null) reservedNoteIds.add(noteMeta.noteId());
        }
        int imported = 0;

        for (CardFile cardFile : cardFiles) {
            String oldCardId = cardFile.meta() == null ? null : cardFile.meta().getString("cardId");
            if (isManaged(cardFile, oldCardId, managedCardIds, index, cardFilesById)) continue;

            String cardId = chooseCardId(oldCardId, cardFile, managedCardIds, index, cardFilesById);
            JSONObject cardMeta = completeCardMeta(cardFile.meta(), cardId);
            fileHelper.fileWritten(cardFile.path(), composeCard(cardMeta, cardFile.content()));

            String noteId = uniqueId(reservedNoteIds);
            reservedNoteIds.add(noteId);
            String noteFileName = "tmp-" + noteId + ".json";
            index.cardFiles.put(cardId, cardFile.fileName());
            index.noteFiles.put(noteId, noteFileName);
            fileHelper.fileWritten(indexFile, index);

            JSONObject noteMeta = new JSONObject();
            noteMeta.put("v", 0);
            noteMeta.put("noteId", noteId);
            noteMeta.put("title", title(cardFile.fileName()));
            noteMeta.put("type", "note");
            noteMeta.put("notebookId", DEFAULT_NOTEBOOK_ID);
            noteMeta.put("cardIds", List.of(cardId));
            fileHelper.fileWritten(metaDir.resolve(noteFileName), noteMeta);

            managedCardIds.add(cardId);
            imported++;
            log.info("已将卡片 {} 导入笔记 {}", cardFile.fileName(), noteId);
        }

        if (imported > 0) {
            log.info("本轮扫描导入 {} 张卡片", imported);
        }
    }

    private List<Path> listCardPaths() throws IOException {
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cardsDir, "*.md")) {
            for (Path file : files) {
                if (Files.isRegularFile(file)) paths.add(file);
            }
        }
        paths.sort(Comparator.comparing((Path path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER).thenComparing(path -> path.getFileName().toString()));
        return paths;
    }

    private List<NoteMetaFile> readNoteMetas() throws IOException {
        if (!Files.isDirectory(metaDir)) return List.of();

        List<NoteMetaFile> noteMetas = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(metaDir, "*.json")) {
            for (Path file : files) {
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                if (raw.isBlank()) continue;

                JSONObject json;
                try {
                    json = JSON.parseObject(raw);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("笔记元数据无法解析：" + file, e);
                }
                if (json == null) throw new IllegalStateException("笔记元数据为空：" + file);

                Set<String> cardIds = new HashSet<>();
                JSONArray ids = json.getJSONArray("cardIds");
                if (ids != null) {
                    for (Object value : ids) {
                        if (value != null && !value.toString().isBlank()) cardIds.add(value.toString());
                    }
                }
                noteMetas.add(new NoteMetaFile(json.getString("noteId"), file.getFileName().toString(), cardIds));
            }
        }
        return noteMetas;
    }

    private CardFile readCardFile(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        int newline = raw.indexOf('\n');
        String firstLine = (newline < 0 ? raw : raw.substring(0, newline)).strip();
        if (firstLine.startsWith(META_PREFIX) && firstLine.endsWith(META_SUFFIX)) {
            String json = firstLine.substring(META_PREFIX.length(), firstLine.length() - META_SUFFIX.length()).strip();
            try {
                JSONObject meta = JSON.parseObject(json);
                if (meta != null) {
                    String content = newline < 0 ? "" : raw.substring(newline + 1);
                    return new CardFile(file, file.getFileName().toString(), meta, content);
                }
            } catch (RuntimeException ignored) {
                // Keep an invalid header as source content; the imported card receives a new valid header.
            }
        }
        return new CardFile(file, file.getFileName().toString(), null, raw);
    }

    private IndexData readIndex(List<NoteMetaFile> noteMetas,
                                List<CardFile> cardFiles) throws IOException {
        if (Files.exists(indexFile)) {
            String raw = Files.readString(indexFile, StandardCharsets.UTF_8);
            if (!raw.isBlank()) {
                try {
                    IndexData index = JSON.parseObject(raw, IndexData.class);
                    if (index != null) {
                        index.normalize();
                        return index;
                    }
                } catch (RuntimeException e) {
                    throw new IllegalStateException("ID-文件索引无法解析：" + indexFile, e);
                }
            }
        }

        IndexData index = new IndexData();
        for (NoteMetaFile noteMeta : noteMetas) {
            if (noteMeta.noteId() == null || noteMeta.noteId().isBlank()) continue;
            String previous = index.noteFiles.putIfAbsent(noteMeta.noteId(), noteMeta.fileName());
            if (previous != null && !previous.equals(noteMeta.fileName())) {
                throw new IllegalStateException("笔记 ID 重复：" + noteMeta.noteId());
            }
        }
        for (CardFile cardFile : cardFiles) {
            String cardId = cardFile.meta() == null ? null : cardFile.meta().getString("cardId");
            if (cardId != null && !cardId.isBlank()) index.cardFiles.putIfAbsent(cardId, cardFile.fileName());
        }
        return index;
    }

    private Map<String, List<CardFile>> groupByCardId(List<CardFile> cardFiles) {
        Map<String, List<CardFile>> grouped = new HashMap<>();
        for (CardFile cardFile : cardFiles) {
            String cardId = cardFile.meta() == null ? null : cardFile.meta().getString("cardId");
            if (cardId != null && !cardId.isBlank()) {
                grouped.computeIfAbsent(cardId, ignored -> new ArrayList<>()).add(cardFile);
            }
        }
        return grouped;
    }

    private boolean isManaged(CardFile cardFile,
                              String cardId,
                              Set<String> managedCardIds,
                              IndexData index,
                              Map<String, List<CardFile>> cardFilesById) {
        if (cardId == null || !managedCardIds.contains(cardId)) return false;

        String indexedFile = index.cardFiles.get(cardId);
        if (indexedFile != null) return indexedFile.equals(cardFile.fileName());

        List<CardFile> sameIdFiles = cardFilesById.getOrDefault(cardId, List.of());
        return !sameIdFiles.isEmpty() && sameIdFiles.get(0).path().equals(cardFile.path());
    }

    private String chooseCardId(String oldCardId,
                                CardFile cardFile,
                                Set<String> managedCardIds,
                                IndexData index,
                                Map<String, List<CardFile>> cardFilesById) {
        if (oldCardId == null || oldCardId.isBlank() || managedCardIds.contains(oldCardId)) {
            return uniqueId(reservedCardIds(managedCardIds, index, cardFilesById));
        }

        String indexedFile = index.cardFiles.get(oldCardId);
        if (indexedFile != null) {
            return indexedFile.equals(cardFile.fileName()) ? oldCardId : uniqueId(reservedCardIds(managedCardIds, index, cardFilesById));
        }

        List<CardFile> sameIdFiles = cardFilesById.getOrDefault(oldCardId, List.of());
        if (!sameIdFiles.isEmpty() && !sameIdFiles.get(0).path().equals(cardFile.path())) {
            return uniqueId(reservedCardIds(managedCardIds, index, cardFilesById));
        }
        return oldCardId;
    }

    private Set<String> reservedCardIds(Set<String> managedCardIds,
                                        IndexData index,
                                        Map<String, List<CardFile>> cardFilesById) {
        Set<String> values = union(index.cardFiles.keySet(), managedCardIds);
        values.addAll(cardFilesById.keySet());
        return values;
    }

    private Set<String> union(Set<String> left,
                              Set<String> right) {
        Set<String> values = new HashSet<>(left);
        values.addAll(right);
        return values;
    }

    private String uniqueId(Iterable<String> existingIds) {
        Set<String> existing = new HashSet<>();
        existingIds.forEach(existing::add);
        String id;
        do {
            id = UUID.randomUUID().toString();
        } while (existing.contains(id));
        return id;
    }

    private JSONObject completeCardMeta(JSONObject current,
                                        String cardId) {
        JSONObject meta = current == null ? new JSONObject() : current;
        putDefault(meta, "v", 2);
        meta.put("cardId", cardId);
        putDefault(meta, "x", 0d);
        putDefault(meta, "y", 0d);
        putDefault(meta, "w", 720d);
        putDefault(meta, "h", 260d);
        putDefault(meta, "z", 0);
        putDefault(meta, "collapsed", false);
        putDefault(meta, "ext", new JSONObject());
        return meta;
    }

    private void putDefault(JSONObject json,
                            String key,
                            Object value) {
        if (json.get(key) == null) json.put(key, value);
    }

    private String composeCard(JSONObject meta,
                               String content) {
        String json = JSON.toJSONString(meta).replace("\r", " ").replace("\n", " ");
        return META_PREFIX + json + META_SUFFIX + "\n" + content;
    }

    private String title(String fileName) {
        return fileName.substring(0, fileName.length() - ".md".length());
    }

    private record NoteMetaFile(String noteId, String fileName, Set<String> cardIds) {
    }

    private record CardFile(Path path, String fileName, JSONObject meta, String content) {
    }

    public static class IndexData {
        public Map<String, String> noteFiles = new LinkedHashMap<>();
        public Map<String, String> cardFiles = new LinkedHashMap<>();

        private void normalize() {
            if (noteFiles == null) noteFiles = new LinkedHashMap<>();
            if (cardFiles == null) cardFiles = new LinkedHashMap<>();
        }
    }
}
