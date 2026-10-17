package com.thisnode.ext.notes.repository;

import com.thisnode.ext.notes.config.NotesConfig;
import com.thisnode.ext.notes.model.CardPO;
import com.thisnode.ext.notes.model.CardPO.CardMeta;
import com.thisnode.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class CardRepository {

    public static final int MIN_W = 240;
    public static final int MIN_H = 88;

    private final NotesConfig notesConfig;
    private final CardFileIndex fileIndex;
    private final FileHelper fileHelper;

    @SneakyThrows
    public CardPO getOne(String cardId) {
        if (cardId == null || cardId.isBlank()) return null;
        String fileName = fileIndex.cardFileName(cardId);
        if (fileName == null) return null;
        Path file = notesConfig.getCardsDir().resolve(fileName);
        try {
            return CardPO.parse(file);
        } catch (NoSuchFileException e) {
            return null;
        }
    }

    public List<CardPO> getAll(List<String> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) return List.of();
        List<CardPO> cards = new ArrayList<>(cardIds.size());
        for (String cardId : cardIds) {
            CardPO card = getOne(cardId);
            if (card != null && card.getMeta() != null) cards.add(card);
        }
        return cards;
    }

    public List<CardPO> getAllIndexed() {
        return getAll(new ArrayList<>(fileIndex.cardFiles().keySet()));
    }

    public List<CardPO> listChangedFiles(Instant since) throws IOException {
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

        List<CardPO> cards = new ArrayList<>(paths.size());
        for (Path path : paths) {
            cards.add(CardPO.parse(path));
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
            BasicFileAttributes attributes;
            try {
                attributes = Files.readAttributes(entry, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                return false;
            }
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
     * 最终文件名都会回填到 meta.sourceFileName。
     */
    public String saveNew(CardMeta meta,
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
            fileHelper.fileWritten(dir.resolve(fileName), CardPO.compose(meta, content));
        } else {
            if (!fileName.endsWith(".md")) {
                throw new IllegalArgumentException("卡片文件名必须是 Markdown 文件名：" + fileName);
            }
            Path safeDir = dir.toAbsolutePath().normalize();
            Path file = safeDir.resolve(fileName).normalize();
            if (!fileName.equals(file.getFileName().toString()) || !safeDir.equals(file.getParent())) {
                throw new IllegalArgumentException("卡片文件名必须是 cards 目录下的文件名：" + fileName);
            }
            fileHelper.fileWritten(file, CardPO.compose(meta, content));
        }

        meta.setSourceFileName(fileName);
        fileIndex.addCard(meta.getCardId(), fileName);
        return meta.getCardId();
    }

    @SneakyThrows
    public void renameFiles(List<String> cardIds,
                            String noteTitle) {
        if (cardIds == null || cardIds.isEmpty()) return;

        Path dir = notesConfig.getCardsDir();
        boolean draftTitle = noteTitle == null || noteTitle.isBlank();
        for (String cardId : cardIds) {
            String oldFileName = fileIndex.cardFileName(cardId);
            if (oldFileName == null) continue;

            String prefix = draftTitle ? "draft-" + cardId : noteTitle;
            for (int number = 1; ; number++) {
                String newFileName = prefix + "_" + number + ".md";
                if (newFileName.equals(oldFileName)) break;
                Path newFile = dir.resolve(newFileName);
                if (Files.exists(newFile)) continue;

                Path oldFile = dir.resolve(oldFileName);
                fileHelper.fileMoved(oldFile, newFile);
                fileIndex.updateCardFileName(cardId, newFileName);
                break;
            }
        }
    }

    /**
     * Rewrite the card content using its stable ID to resolve the physical path.
     */
    public String save(CardMeta meta,
                       String contentMd,
                       String cardId) {
        String fileName = fileIndex.cardFileName(cardId);
        if (fileName == null) throw new IllegalStateException("卡片 ID 不在文件索引中：" + cardId);
        Path file = notesConfig.getCardsDir().resolve(fileName);
        fileHelper.fileWritten(file, CardPO.compose(meta, contentMd));
        return cardId;
    }

    public void save(CardMeta meta,
                     String cardId) {
        CardPO card = getOne(cardId);
        String body = card == null ? "" : card.getContent();
        this.save(meta, body, cardId);
    }

    public void delete(List<String> cardIds) {
        if (cardIds == null) return;
        for (String cardId : cardIds) {
            String fileName = fileIndex.cardFileName(cardId);
            if (fileName == null) continue;
            fileIndex.removeCard(cardId);
            Path file = notesConfig.getCardsDir().resolve(fileName);
            fileHelper.fileDeleted(file);
        }
    }
}
