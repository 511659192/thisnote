package com.thisnote.ext.notes.repository;

import com.alibaba.fastjson2.TypeReference;
import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.ext.notes.model.CardPO;
import com.thisnote.ext.notes.model.CardPO.CardMeta;
import com.thisnote.ext.notes.util.FileNameUtil;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 卡片存储：正文在 {@code cards/*.md}，元数据在 {@code plugins/notes/.card-metadata.json}
 * （{@code cardId → CardMeta}，文件名就是 {@code meta.sourceFileName}）。
 */
@Slf4j
@Repository
public class CardRepository {

    public static final int MIN_W = 240;
    public static final int MIN_H = 88;

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;
    private final Path cardMetadataFile;

    public CardRepository(NotesConfig notesConfig,
                          FileHelper fileHelper) {
        this.notesConfig = notesConfig;
        this.fileHelper = fileHelper;
        this.cardMetadataFile = notesConfig.getRootDir().resolve("plugins").resolve("notes").resolve(".card-metadata.json");
    }

    @SneakyThrows
    public synchronized CardPO getOne(String cardId) {
        if (cardId == null || cardId.isBlank()) return null;

        CardMeta meta = readCardMetadata(cardId);
        if (meta == null || meta.getFileName() == null) return null;

        return readCard(notesConfig.getRootDir().resolve("cards").resolve(meta.getFileName()), meta);
    }

    public List<CardPO> getAll(List<String> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) return List.of();

        return cardIds.stream()
                .map(this::getOne)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 索引里的全部卡片。
     */
    public synchronized List<CardPO> getAllManaged() {
        List<CardPO> cards = new ArrayList<>();
        Map<String, CardMeta> cardMetaMap = readCardMetadata();
        for (CardMeta meta : cardMetaMap.values()) {
            if (meta == null || meta.getFileName() == null) continue;

            try {
                cards.add(readCard(notesConfig.getRootDir().resolve("cards").resolve(meta.getFileName()), meta));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return cards;
    }

    /**
     * cards 目录里、卡片索引中没有的 md 就是「无主卡片」：meta 为 null，交给导入插件处理。
     */
    public synchronized List<CardPO> listUnowned() throws IOException {
        Path cardsDir = notesConfig.getRootDir().resolve("cards");
        if (!Files.isDirectory(cardsDir)) return List.of();

        Map<String, CardMeta> metaByFileName = metaByFileName();
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cardsDir, markdownFiles())) {
            for (Path file : files) {
                if (!metaByFileName.containsKey(file.getFileName().toString())) {
                    paths.add(file);
                }
            }
        }
        paths.sort(Comparator.comparing((Path path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER).thenComparing(path -> path.getFileName().toString()));

        List<CardPO> cards = new ArrayList<>(paths.size());
        for (Path path : paths) {
            cards.add(readCard(path, null));
        }
        return List.copyOf(cards);
    }

    /**
     * 后缀过滤器：只留 Markdown 卡片文件（glob 语义与 Files.newDirectoryStream(dir, "*.md") 一致）。
     */
    private static DirectoryStream.Filter<Path> markdownFiles() {
        PathMatcher markdown = FileSystems.getDefault().getPathMatcher("glob:*.md");
        return entry -> markdown.matches(entry.getFileName()) && Files.isRegularFile(entry);
    }

    /**
     * Write a card into the cards directory and register it in the index, returning its ID.
     *
     * <p>fileName 为空 = 新建：补齐稳定 ID，并按标题派生唯一文件名（标题_1.md…）；
     * fileName 非空 = 显式命名：必须是 cards 目录下的 Markdown 文件名。
     * 最终文件名回填到 meta.sourceFileName 并随元数据写进卡片索引。
     */
    public synchronized String save(CardMeta meta,
                                    String content,
                                    String fileName,
                                    String title) {
        if (meta.getCardId() == null || meta.getCardId().isBlank()) {
            meta.setCardId(UUID.randomUUID().toString());
        }

        Path dir = notesConfig.getRootDir().resolve("cards");
        if (fileName == null || fileName.isBlank()) {
            String prefix = title == null || title.isBlank() ? "tmp-" + meta.getCardId() : title;
            fileName = FileNameUtil.nextAvailable(dir, prefix, ".md", null);
        } else {
            if (!fileName.endsWith(".md")) {
                throw new IllegalArgumentException("卡片文件名必须是 Markdown 文件名：" + fileName);
            }
            Path safeDir = dir.toAbsolutePath().normalize();
            Path file = safeDir.resolve(fileName).normalize();
            if (!fileName.equals(file.getFileName().toString()) || !safeDir.equals(file.getParent())) {
                throw new IllegalArgumentException("卡片文件名必须是 cards 目录下的文件名：" + fileName);
            }
        }

        meta.setFileName(fileName);
        fileHelper.fileWritten(dir.resolve(fileName), content == null ? "" : content);
        putCardIndexMeta(meta);
        return meta.getCardId();
    }

    /**
     * note输入标题后 对应的card文件重命名
     * @param cardIds
     * @param noteTitle
     */
    public synchronized void renameFiles(List<String> cardIds,
                                         String noteTitle) {
        if (cardIds == null || cardIds.isEmpty()) return;

        Path dir = notesConfig.getRootDir().resolve("cards");
        boolean draftTitle = noteTitle == null || noteTitle.isBlank();
        for (String cardId : cardIds) {
            CardMeta meta = readCardMetadata(cardId);
            if (meta == null || meta.getFileName() == null) continue;

            String oldFileName = meta.getFileName();
            String prefix = draftTitle ? "draft-" + cardId : noteTitle;
            String newFileName = FileNameUtil.nextAvailable(dir, prefix, ".md", oldFileName);
            if (newFileName.equals(oldFileName)) continue;

            fileHelper.rename(dir.resolve(oldFileName), dir.resolve(newFileName));
            meta.setFileName(newFileName);
            putCardIndexMeta(meta);
        }
    }

    /**
     * Rewrite the card content using its stable ID to resolve the physical path.
     * {@code contentMd} 为 null = 只更新元数据（几何/颜色…）：正文与 md 文件都不动，mtime 保持不变。
     */
    public synchronized String update(CardMeta meta,
                                      String contentMd,
                                      String cardId) {
        CardMeta indexed = readCardMetadata(cardId);
        if (indexed == null || indexed.getFileName() == null) {
            throw new IllegalStateException("卡片 ID 不在索引中：" + cardId);
        }
        meta.setFileName(indexed.getFileName());
        if (contentMd != null) {
            fileHelper.fileWritten(notesConfig.getRootDir().resolve("cards").resolve(indexed.getFileName()), contentMd);
        }
        putCardIndexMeta(meta);
        return cardId;
    }

    /**
     * 卡片索引元数据；不在索引或缺文件名返回 null。比 {@link #getOne} 便宜：不读卡片正文。
     */
    public synchronized CardMeta getMeta(String cardId) {
        if (cardId == null || cardId.isBlank()) return null;
        CardMeta meta = readCardMetadata(cardId);
        return meta == null || meta.getFileName() == null ? null : meta;
    }

    /**
     * 批量更新元数据（几何/颜色…）：索引一次读、合并改、一次写回，避免逐张全量重写。
     * 任一 cardId 不在索引中则整体抛出、不落盘。
     */
    public synchronized void updateAll(Map<String, CardMeta> metas) {
        if (metas.isEmpty()) return;
        Map<String, CardMeta> index = readCardMetadata();
        for (Map.Entry<String, CardMeta> entry : metas.entrySet()) {
            CardMeta indexed = index.get(entry.getKey());
            if (indexed == null || indexed.getFileName() == null) {
                throw new IllegalStateException("卡片 ID 不在索引中：" + entry.getKey());
            }
            entry.getValue().setFileName(indexed.getFileName());
            index.put(entry.getKey(), entry.getValue());
        }
        fileHelper.fileWritten(cardMetadataFile, index);
    }

    public synchronized void delete(List<String> cardIds) {
        if (cardIds == null) return;

        for (String cardId : cardIds) {
            CardMeta meta = readCardMetadata(cardId);
            if (meta == null) continue;
            deleteIndexMeta(cardId);
            if (meta.getFileName() != null) {
                Path cardFile = notesConfig.getRootDir().resolve("cards").resolve(meta.getFileName());
                if (!fileHelper.fileDeleted(cardFile) && Files.exists(cardFile)) {
                    log.error("删除卡片文件失败：{}", cardFile);
                }
            }
        }
    }

    private static CardPO readCard(Path file, CardMeta meta) throws IOException {
        CardPO card = CardPO.parse(file);
        card.setMeta(meta);
        return card;
    }

    private CardMeta readCardMetadata(String cardId) {
        return cardId == null ? null : readCardMetadata().get(cardId);
    }

    private Map<String, CardMeta> metaByFileName() {
        Map<String, CardMeta> byFileName = new LinkedHashMap<>();
        for (CardMeta meta : readCardMetadata().values()) {
            if (meta != null && meta.getFileName() != null) {
                byFileName.put(meta.getFileName(), meta);
            }
        }
        return byFileName;
    }

    private void putCardIndexMeta(CardMeta meta) {
        if (meta == null || meta.getCardId() == null || meta.getCardId().isBlank()) {
            throw new IllegalArgumentException("卡片 ID 不能为空");
        }
        if (meta.getFileName() == null || meta.getFileName().isBlank()) {
            throw new IllegalArgumentException("卡片文件名不能为空：" + meta.getCardId());
        }
        Map<String, CardMeta> metas = readCardMetadata();
        metas.put(meta.getCardId(), meta);
        fileHelper.fileWritten(cardMetadataFile, metas);
    }

    private void deleteIndexMeta(String cardId) {
        Map<String, CardMeta> metas = readCardMetadata();
        if (metas.remove(cardId) != null) {
            fileHelper.fileWritten(cardMetadataFile, metas);
        }
    }

    /**
     * 状态重置：把卡片索引重写为空 map（md 文件保留）。
     */
    public synchronized void reset() {
        fileHelper.fileWritten(cardMetadataFile, new LinkedHashMap<>());
    }

    private Map<String, CardMeta> readCardMetadata() {
        return FileHelper.readMapIndex(cardMetadataFile, new TypeReference<LinkedHashMap<String, CardMeta>>() { }, "卡片");
    }
}
