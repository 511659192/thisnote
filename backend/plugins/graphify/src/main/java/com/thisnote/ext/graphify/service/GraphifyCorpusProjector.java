package com.thisnote.ext.graphify.service;

import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.util.FileHelper;
import com.thisnote.plugin.api.util.MarkdownImages;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class GraphifyCorpusProjector {

    private static final long IMAGE_INLINE_LIMIT = 5L * 1024 * 1024;

    private final FileHelper fileHelper;
    private final Path corpusDir;
    private final Path graphifyOutDir;

    public GraphifyCorpusProjector(FileHelper fileHelper,
                                   Path corpusDir,
                                   Path graphifyOutDir) {
        this.fileHelper = fileHelper;
        this.corpusDir = corpusDir;
        this.graphifyOutDir = graphifyOutDir;
    }

    public ProjectionMetadata project(NoteDTO note,
                                      List<CardDTO> cards,
                                      List<String> warnings,
                                      Function<String, byte[]> attachmentLoader) {
        log.debug("Graphify 投影开始：noteId={}, titleLength={}, cards={}",
                note.noteId(), note.title() == null ? 0 : note.title().length(),
                cards == null ? 0 : cards.size());
        Map<String, CardDTO> cardsById = cards == null ? new HashMap<>() : cards.stream()
                .filter(card -> card != null && card.meta() != null && card.meta().cardId() != null)
                .collect(Collectors.toMap(card -> card.meta().cardId(), card -> card, (first, second) -> second));

        List<String> cardIds = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        List<String> order = note.cardIds() == null ? List.of() : note.cardIds();
        for (String cardId : order) {
            CardDTO card = cardsById.get(cardId);
            if (card == null) {
                continue;
            }
            cardIds.add(cardId);
            bodies.add(card.content() == null ? "" : card.content());
        }

        StringBuilder markdown = new StringBuilder("# ")
                .append(note.title() == null ? "" : note.title().replace('\n', ' ').replace('\r', ' '))
                .append("\n\n");
        for (int i = 0; i < bodies.size(); i++) {
            if (i > 0) {
                markdown.append("\n\n---\n\n");
            }
            markdown.append(bodies.get(i));
        }

        RewrittenMarkdown rewritten = rewriteAttachmentTargets(markdown.toString());
        markdown = new StringBuilder(rewritten.markdown());
        Set<String> attachmentNames = rewritten.attachmentNames();
        log.debug("Graphify 图片引用处理完成：noteId={}, attachments={}",
                note.noteId(), attachmentNames.size());

        Path noteDir = noteDirectory(note.noteId());
        Set<String> copiedAttachments = new LinkedHashSet<>();
        boolean changed = false;
        for (String fileName : attachmentNames) {
            log.debug("Graphify 读取投影附件：noteId={}, fileName={}", note.noteId(), fileName);
            byte[] bytes = attachmentLoader.apply(fileName);
            if (bytes == null) {
                warnings.add("图片附件不存在或无法读取：" + fileName);
                continue;
            }
            Path destination = noteDir.resolve("attachments").resolve(fileName);
            if (!sameBytes(destination, bytes)) {
                fileHelper.fileWritten(destination, new ByteArrayInputStream(bytes), 0);
                changed = true;
                log.debug("Graphify 投影附件已写入：noteId={}, fileName={}, bytes={}",
                        note.noteId(), fileName, bytes.length);
            }
            copiedAttachments.add(fileName);
            if (bytes.length > IMAGE_INLINE_LIMIT) {
                warnings.add("图片超过 Graphify 视觉输入上限 5 MiB，仍会作为图片引用索引：" + fileName);
            }
        }
        changed |= removeStaleAttachments(noteDir, copiedAttachments);
        Path noteFile = noteDir.resolve("note.md");
        if (!sameText(noteFile, markdown.toString())) {
            fileHelper.fileWritten(noteFile, markdown.toString());
            changed = true;
            log.debug("Graphify 投影正文已写入：noteId={}, file={}", note.noteId(), noteFile);
        }
        ProjectionMetadata result = new ProjectionMetadata(cardIds, attachmentNames, changed);
        log.debug("Graphify 投影完成：noteId={}, cards={}, attachments={}, changed={}",
                note.noteId(), result.cardIds().size(), result.attachmentNames().size(), result.changed());
        return result;
    }

    static RewrittenMarkdown rewriteAttachmentTargets(String source) {
        List<MarkdownImages.Reference> references = MarkdownImages.attachmentReferences(source);
        Set<String> attachmentNames = new LinkedHashSet<>();
        for (MarkdownImages.Reference reference : references) {
            attachmentNames.add(reference.fileName());
        }
        StringBuilder rewritten = new StringBuilder(source);
        references.stream()
                .sorted((left, right) -> Integer.compare(right.start(), left.start()))
                .forEach(reference -> {
                    String replacement = reference.originalTarget().startsWith("<") || reference.fileName().contains(" ")
                            ? "<attachments/" + reference.fileName() + ">"
                            : "attachments/" + reference.fileName();
                    rewritten.replace(reference.start(), reference.end(), replacement);
                });
        return new RewrittenMarkdown(rewritten.toString(), Collections.unmodifiableSet(attachmentNames));
    }

    public void remove(String noteId) {
        Path noteDir = noteDirectory(noteId);
        if (!Files.exists(noteDir)) {
            log.debug("Graphify 投影删除跳过：noteId={}, directoryMissing=true", noteId);
            return;
        }
        log.debug("Graphify 投影删除开始：noteId={}, directory={}", noteId, noteDir);
        try {
            List<Path> reverse;
            try (var paths = Files.walk(noteDir)) {
                reverse = paths.sorted(Comparator.reverseOrder()).toList();
            }
            for (Path path : reverse) {
                if (Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    if (!fileHelper.fileDeleted(path) && Files.exists(path)) {
                        log.error("删除 Graphify 文件失败：{}", path);
                    }
                } else {
                    Files.deleteIfExists(path);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("删除笔记投影失败", e);
        }
        log.debug("Graphify 投影删除完成：noteId={}", noteId);
    }

    private boolean removeStaleAttachments(Path noteDir,
                                           Set<String> retained) {
        Path attachmentDir = noteDir.resolve("attachments");
        if (!Files.isDirectory(attachmentDir)) {
            return false;
        }
        try {
            List<Path> files;
            try (var listed = Files.list(attachmentDir)) {
                files = listed.toList();
            }
            boolean changed = false;
            for (Path file : files) {
                if (Files.isRegularFile(file) && !retained.contains(file.getFileName().toString())) {
                    if (!fileHelper.fileDeleted(file) && Files.exists(file)) {
                        log.error("删除 Graphify 文件失败：{}", file);
                    }
                    changed = true;
                }
            }
            boolean empty;
            try (var remaining = Files.list(attachmentDir)) {
                empty = remaining.findAny().isEmpty();
            }
            if (empty) {
                Files.deleteIfExists(attachmentDir);
            }
            return changed;
        } catch (Exception e) {
            throw new IllegalStateException("清理笔记投影图片失败", e);
        }
    }

    private static boolean sameText(Path path,
                                    String content) {
        try {
            return Files.isRegularFile(path) && Files.readString(path, StandardCharsets.UTF_8).equals(content);
        } catch (Exception e) {
            log.error("读取 Graphify 文本投影失败：{}", path, e);
            return false;
        }
    }

    private static boolean sameBytes(Path path,
                                     byte[] content) {
        try {
            return Files.isRegularFile(path)
                    && Files.size(path) == content.length
                    && Arrays.equals(Files.readAllBytes(path), content);
        } catch (Exception e) {
            log.error("读取 Graphify 二进制投影失败：{}", path, e);
            return false;
        }
    }

    private Path noteDirectory(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        Path target = corpusDir.resolve(noteId).normalize();
        if (!target.startsWith(corpusDir) || target.equals(corpusDir) || target.equals(graphifyOutDir)) {
            throw new IllegalArgumentException("noteId 不是有效的目录名称");
        }
        return target;
    }

    public record ProjectionMetadata(List<String> cardIds,
                                     Set<String> attachmentNames,
                                     boolean changed) {
    }

    record RewrittenMarkdown(String markdown, Set<String> attachmentNames) {
    }
}
