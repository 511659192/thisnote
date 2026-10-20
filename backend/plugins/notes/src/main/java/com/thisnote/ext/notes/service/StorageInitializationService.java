package com.thisnote.ext.notes.service;

import com.thisnote.ext.notes.config.NotesConfig;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 首次启动时为真正空的数据目录补齐索引；任何已有内容都不会被覆盖。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageInitializationService {

    private final NotesConfig notesConfig;
    private final FileHelper fileHelper;

    public synchronized void initialize() {
        Path root = notesConfig.getRootDir();
        Path cards = root.resolve("cards");
        Path attachments = root.resolve("attachments");
        Path pluginDir = root.resolve("plugins").resolve("notes");
        log.info("开始初始化笔记存储：root={}", root);
        try {
            Files.createDirectories(cards);
            Files.createDirectories(root.resolve("book"));
            Files.createDirectories(attachments);
            Files.createDirectories(pluginDir);
        } catch (IOException e) {
            log.error("创建笔记数据目录失败：root={}", root, e);
            throw new IllegalStateException("无法创建笔记数据目录：" + root, e);
        }

        boolean hasCards = contains(cards, ".md");
        boolean hasAttachments = contains(attachments, ".png", ".jpg", ".jpeg", ".webp");
        boolean noteIndexCreated = initializeIndex(pluginDir.resolve(".note-metadata.json"), hasCards, "卡片正文");
        boolean cardIndexCreated = initializeIndex(pluginDir.resolve(".card-metadata.json"), hasCards, "卡片正文");
        boolean attachmentIndexCreated = initializeIndex(pluginDir.resolve(".attachment-metadata.json"), hasAttachments, "附件文件");
        log.info("笔记存储初始化完成：root={}, noteIndexCreated={}, cardIndexCreated={}, attachmentIndexCreated={}",
                root, noteIndexCreated, cardIndexCreated, attachmentIndexCreated);
    }

    private boolean initializeIndex(Path file,
                                    boolean hasContent,
                                    String contentLabel) {
        if (Files.exists(file)) {
            log.debug("索引已存在，跳过初始化：file={}", file);
            return false;
        }
        if (hasContent) {
            log.error("数据目录已有{}但索引缺失，拒绝自动初始化：file={}, contentLabel={}",
                    contentLabel, file, contentLabel);
            throw new IllegalStateException("数据目录已有" + contentLabel + "，但索引缺失：" + file
                    + "。为避免破坏已有数据，已停止自动初始化，请先恢复索引或备份数据。");
        }
        fileHelper.fileWritten(file, "{}");
        log.info("已创建空索引：file={}", file);
        return true;
    }

    private static boolean contains(Path directory,
                                    String... suffixes) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) continue;
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String suffix : suffixes) {
                    if (name.endsWith(suffix)) return true;
                }
            }
            return false;
        } catch (IOException e) {
            log.error("读取数据目录失败：directory={}", directory, e);
            throw new IllegalStateException("无法读取数据目录：" + directory, e);
        }
    }
}
