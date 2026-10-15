package com.thisnode.plugin.api.util;

import com.alibaba.fastjson2.JSON;
import com.thisnode.plugin.api.FileChangeEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

/** Executes persistent file mutations and publishes their plugin-scoped change events. */
public final class FileHelper {

    private static final Logger log = LoggerFactory.getLogger(FileHelper.class);

    private final FileChangeEventPublisher fileChangeEventPublisher;

    @Autowired
    public FileHelper(FileChangeEventPublisher fileChangeEventPublisher) {
        this.fileChangeEventPublisher = Objects.requireNonNull(fileChangeEventPublisher, "fileChangeEventPublisher");
    }

    public static void registerSpringBeans(GenericApplicationContext context,
                                           ApplicationEventPublisher mainEventPublisher,
                                           String sourcePluginId) {
        context.registerBean(FileChangeEventPublisher.class,
                () -> new FileChangeEventPublisher(mainEventPublisher, sourcePluginId));
        context.registerBean(FileHelper.class);
    }

    public void fileWritten(Path file,
                            Object content) {
        fileWritten(file, JSON.toJSONString(content));
    }

    public void fileWritten(Path file,
                            String content) {
        Objects.requireNonNull(content, "content");
        boolean previouslyExisted = Files.exists(file);
        try {
            createParentDirectories(file);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("无法写入文件 {}", file, e);
            throw new UncheckedIOException("写入文件失败 " + file, e);
        }
        fileChangeEventPublisher.fileWritten(file, previouslyExisted);
    }

    public void fileWritten(Path destination,
                            MultipartFile file) {
        boolean previouslyExisted = Files.exists(destination);
        try {
            createParentDirectories(destination);
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("无法写入文件 {}", destination, e);
            throw new UncheckedIOException("保存图片附件失败", e);
        }
        fileChangeEventPublisher.fileWritten(destination, previouslyExisted);
        log.info("保存图片附件 {}", destination.getFileName());
    }

    public void fileWritten(Path file,
                            Path stagedFile) {
        Objects.requireNonNull(stagedFile, "stagedFile");
        boolean previouslyExisted = Files.exists(file);
        try {
            createParentDirectories(file);
            Files.move(stagedFile, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("无法写入文件 {}", file, e);
            throw new UncheckedIOException("写入文件失败 " + file, e);
        }
        fileChangeEventPublisher.fileWritten(file, previouslyExisted);
    }

    public boolean fileDeleted(Path file) {
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                fileChangeEventPublisher.fileDeleted(file);
            }
            return deleted;
        } catch (IOException e) {
            log.warn("无法删除文件 {}", file, e);
            return false;
        }
    }

    public Path fileMoved(Path previousFile,
                          Path file,
                          CopyOption... options) {
        Path moved;
        try {
            moved = Files.move(previousFile, file, options);
        } catch (IOException e) {
            log.warn("无法移动文件 {} -> {}", previousFile, file, e);
            throw new UncheckedIOException("移动文件失败 " + previousFile + " -> " + file, e);
        }
        fileChangeEventPublisher.fileMoved(previousFile, moved);
        return moved;
    }

    private void createParentDirectories(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
