package com.thisnode.ext.notes.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.CardIndex;
import com.thisnode.ext.notes.model.CardPO;
import com.thisnode.ext.notes.model.CardPO.CardMeta;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 卡片存储：正文在 {@code cards/*.md}，元数据在 {@code plugins/notes/.card-index.json}
 * （{@code cardId → CardMeta}，文件名就是 {@code meta.sourceFileName}）。
 *
 * <p>旧索引（notes 根目录的 {@code .card-file-index.json}）只有 cardId → 文件名、
 * 没有元数据；按约定不做历史迁移，首次读取时直接换成空的新索引。
 */
@Slf4j
@Repository
public class CardRepository {

    public static final int MIN_W = 240;
    public static final int MIN_H = 88;

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;
    private final Path indexFile;
    private final Path legacyIndexFile;

    public CardRepository(NotesConfig notesConfig,
                          FileHelper fileHelper) {
        this.notesConfig = notesConfig;
        this.fileHelper = fileHelper;
        this.indexFile = notesConfig.getNotesPluginDir().resolve(".card-index.json");
        this.legacyIndexFile = notesConfig.getRootDir().resolve(".card-file-index.json");
    }

    @SneakyThrows
    public synchronized CardPO getOne(String cardId) {
        if (cardId == null || cardId.isBlank()) return null;
        CardMeta meta = indexMeta(cardId);
        if (meta == null || meta.getSourceFileName() == null) return null;
        return readCard(notesConfig.getCardsDir().resolve(meta.getSourceFileName()), meta);
    }

    public List<CardPO> getAll(List<String> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) return List.of();
        List<CardPO> cards = new ArrayList<>(cardIds.size());
        for (String cardId : cardIds) {
            CardPO card = getOne(cardId);
            if (card != null) cards.add(card);
        }
        return cards;
    }

    /**
     * 索引里的全部卡片。
     */
    public synchronized List<CardPO> getAllIndexed() {
        List<CardPO> cards = new ArrayList<>();
        for (CardMeta meta : readIndex().getCards().values()) {
            if (meta == null || meta.getSourceFileName() == null) continue;
            try {
                cards.add(readCard(notesConfig.getCardsDir().resolve(meta.getSourceFileName()), meta));
            } catch (NoSuchFileException e) {
                // 文件被外部删掉：跳过，下一次扫描会把它当成无主卡片
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return cards;
    }

    public synchronized List<CardPO> listChangedFiles(Instant since) throws IOException {
        Path cardsDir = notesConfig.getCardsDir();
        if (!Files.isDirectory(cardsDir)) return List.of();

        DirectoryStream.Filter<Path> filter = allOf(markdownFiles(), changedSince(since));
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(cardsDir, filter)) {
            for (Path file : files) {
                paths.add(file);
            }
        }
        paths.sort(Comparator.comparing((Path path) -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER).thenComparing(path -> path.getFileName().toString()));

        // 没有索引条目的 md 就是「无主卡片」：meta 为 null，交给导入插件处理。
        Map<String, CardMeta> metaByFileName = metaByFileName();
        List<CardPO> cards = new ArrayList<>(paths.size());
        for (Path path : paths) {
            cards.add(readCard(path, metaByFileName.get(path.getFileName().toString())));
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
     * 时间过滤器：只留 since 之后有改动（写入或迁入）的卡片。
     */
    private static DirectoryStream.Filter<Path> changedSince(Instant since) {
        return entry -> {
            BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class);
            // 迁移（复制/下载）会保留源文件的修改时间，因此同时看创建时间（Unix 上为状态变更时间）
            return !attributes.lastModifiedTime().toInstant().isBefore(since) || !attributes.creationTime().toInstant().isBefore(since);
        };
    }

    /**
     * 两个过滤器依次判定，都命中才算数。
     */
    private static DirectoryStream.Filter<Path> allOf(DirectoryStream.Filter<Path> first,
                                                      DirectoryStream.Filter<Path> second) {
        return entry -> first.accept(entry) && second.accept(entry);
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

        Path dir = notesConfig.getCardsDir();
        if (fileName == null || fileName.isBlank()) {
            String prefix = title == null || title.isBlank() ? "tmp-" + meta.getCardId() : title;
            for (int number = 1; ; number++) {
                fileName = prefix + "_" + number + ".md";
                if (Files.exists(dir.resolve(fileName))) continue;
                break;
            }
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

        meta.setSourceFileName(fileName);
        fileHelper.fileWritten(dir.resolve(fileName), content == null ? "" : content);
        putIndexMeta(meta);
        return meta.getCardId();
    }

    public synchronized void renameFiles(List<String> cardIds,
                                         String noteTitle) {
        if (cardIds == null || cardIds.isEmpty()) return;

        Path dir = notesConfig.getCardsDir();
        boolean draftTitle = noteTitle == null || noteTitle.isBlank();
        for (String cardId : cardIds) {
            CardMeta meta = indexMeta(cardId);
            if (meta == null || meta.getSourceFileName() == null) continue;

            String oldFileName = meta.getSourceFileName();
            String prefix = draftTitle ? "draft-" + cardId : noteTitle;
            for (int number = 1; ; number++) {
                String newFileName = prefix + "_" + number + ".md";
                if (newFileName.equals(oldFileName)) break;
                Path newFile = dir.resolve(newFileName);
                if (Files.exists(newFile)) continue;

                Path oldFile = dir.resolve(oldFileName);
                fileHelper.fileMoved(oldFile, newFile);
                meta.setSourceFileName(newFileName);
                putIndexMeta(meta);
                break;
            }
        }
    }

    /**
     * Rewrite the card content using its stable ID to resolve the physical path.
     */
    public synchronized String update(CardMeta meta,
                                      String contentMd,
                                      String cardId) {
        CardMeta indexed = indexMeta(cardId);
        if (indexed == null || indexed.getSourceFileName() == null) {
            throw new IllegalStateException("卡片 ID 不在索引中：" + cardId);
        }
        meta.setSourceFileName(indexed.getSourceFileName());
        fileHelper.fileWritten(notesConfig.getCardsDir().resolve(indexed.getSourceFileName()), contentMd == null ? "" : contentMd);
        putIndexMeta(meta);
        return cardId;
    }

    /**
     * 只更新元数据（几何/颜色…）：正文与 md 文件都不动，mtime 保持不变。
     */
    public synchronized void update(CardMeta meta,
                                    String cardId) {
        CardMeta indexed = indexMeta(cardId);
        if (indexed == null || indexed.getSourceFileName() == null) {
            throw new IllegalStateException("卡片 ID 不在索引中：" + cardId);
        }
        meta.setSourceFileName(indexed.getSourceFileName());
        putIndexMeta(meta);
    }

    public synchronized void delete(List<String> cardIds) {
        if (cardIds == null) return;
        for (String cardId : cardIds) {
            CardMeta meta = indexMeta(cardId);
            if (meta == null) continue;
            deleteIndexMeta(cardId);
            if (meta.getSourceFileName() != null) {
                fileHelper.fileDeleted(notesConfig.getCardsDir().resolve(meta.getSourceFileName()));
            }
        }
    }

    private static CardPO readCard(Path file, CardMeta meta) throws IOException {
        CardPO card = CardPO.parse(file);
        card.setMeta(meta);
        return card;
    }

    // ------------------------------------------------------------------ 卡片索引

    private CardMeta indexMeta(String cardId) {
        return cardId == null ? null : readIndex().getCards().get(cardId);
    }

    private Map<String, CardMeta> metaByFileName() {
        Map<String, CardMeta> byFileName = new LinkedHashMap<>();
        for (CardMeta meta : readIndex().getCards().values()) {
            if (meta != null && meta.getSourceFileName() != null) {
                byFileName.put(meta.getSourceFileName(), meta);
            }
        }
        return byFileName;
    }

    private void putIndexMeta(CardMeta meta) {
        if (meta == null || meta.getCardId() == null || meta.getCardId().isBlank()) {
            throw new IllegalArgumentException("卡片 ID 不能为空");
        }
        if (meta.getSourceFileName() == null || meta.getSourceFileName().isBlank()) {
            throw new IllegalArgumentException("卡片文件名不能为空：" + meta.getCardId());
        }
        CardIndex index = readIndex();
        index.getCards().put(meta.getCardId(), meta);
        writeIndex(index);
    }

    private void deleteIndexMeta(String cardId) {
        CardIndex index = readIndex();
        if (index.getCards().remove(cardId) != null) {
            writeIndex(index);
        }
    }

    private CardIndex readIndex() {
        relocateLegacyIndex();
        try {
            String json = Files.readString(indexFile, StandardCharsets.UTF_8);
            if (json.isBlank()) {
                return new CardIndex();
            }
            CardIndex index = JSON.parseObject(json, CardIndex.class);
            if (index == null) {
                return new CardIndex();
            }
            if (index.getCards() == null) {
                index.setCards(new LinkedHashMap<>());
            }
            return index;
        } catch (NoSuchFileException e) {
            return new CardIndex();
        } catch (IOException | RuntimeException e) {
            log.error("卡片索引无法解析：{}", indexFile, e);
            throw new IllegalStateException("卡片索引无法解析 " + indexFile, e);
        }
    }

    private void writeIndex(CardIndex index) {
        fileHelper.fileWritten(indexFile, index);
    }

    private void relocateLegacyIndex() {
        if (Files.exists(indexFile) || Files.notExists(legacyIndexFile)) {
            return;
        }
        try {
            // 旧索引只有 cardId → 文件名，没有元数据；新格式每条就是元数据本身。
            // 按约定不做历史迁移：换成空索引，旧映射不保留。
            fileHelper.fileWritten(indexFile, new CardIndex());
            try {
                Files.deleteIfExists(legacyIndexFile);
            } catch (IOException e) {
                log.warn("旧卡片索引已替换，但删除旧文件失败：{}", legacyIndexFile, e);
            }
            log.info("旧卡片索引已替换为 {}（新格式，旧映射未迁移）", indexFile);
        } catch (RuntimeException e) {
            throw new IllegalStateException("旧卡片索引转移失败：" + legacyIndexFile, e);
        }
    }
}
