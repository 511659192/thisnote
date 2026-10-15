package com.thisnode.plugin.api.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.thisnode.plugin.api.event.FileChangeEvent;
import lombok.extern.slf4j.Slf4j;
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
import java.nio.file.attribute.FileTime;
import java.util.Objects;

/**
 * Executes persistent file mutations and publishes their plugin-scoped change events.
 */
@Slf4j
public final class FileHelper {

    private final ApplicationEventPublisher eventPublisher;
    private final String sourcePluginId;

    public FileHelper(ApplicationEventPublisher eventPublisher,
                      String sourcePluginId) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "publisher");
        if (sourcePluginId == null || sourcePluginId.isBlank()) {
            throw new IllegalArgumentException("sourcePluginId 不能为空");
        }
        this.sourcePluginId = sourcePluginId;
    }

    public static void registerSpringBeans(GenericApplicationContext context,
                                           ApplicationEventPublisher mainEventPublisher,
                                           String sourcePluginId) {
        context.registerBean(FileHelper.class, () -> new FileHelper(mainEventPublisher, sourcePluginId));
    }

    public void fileWritten(Path file,
                            Object content) {
        fileWritten(file, JSON.toJSONString(content, JSONWriter.Feature.PrettyFormat));
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
        publishFileChange(previouslyExisted ? FileChangeEvent.Operation.UPDATED : FileChangeEvent.Operation.CREATED, file, null);
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
        publishFileChange(previouslyExisted ? FileChangeEvent.Operation.UPDATED : FileChangeEvent.Operation.CREATED, destination, null);
        log.info("保存图片附件 {}", destination.getFileName());
    }

    public void fileWritten(Path file,
                            Path stagedFile) {
        Objects.requireNonNull(stagedFile, "stagedFile");
        boolean previouslyExisted = Files.exists(file);
        try {
            createParentDirectories(file);
            Files.move(stagedFile, file);
        } catch (IOException e) {
            log.warn("无法写入文件 {}", file, e);
            throw new UncheckedIOException("写入文件失败 " + file, e);
        }
        publishFileChange(previouslyExisted ? FileChangeEvent.Operation.UPDATED : FileChangeEvent.Operation.CREATED, file, null);
    }

    /** Copies and closes the stream, writing directly to the destination before publishing its change event. */
    public void fileWritten(Path file,
                            InputStream content,
                            long modifiedAtMillis) {
        Objects.requireNonNull(content, "content");
        boolean previouslyExisted = Files.exists(file);
        try (InputStream input = content) {
            createParentDirectories(file);
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            if (modifiedAtMillis > 0) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAtMillis));
            }
        } catch (IOException e) {
            log.warn("无法写入文件 {}", file, e);
            throw new UncheckedIOException("写入文件失败 " + file, e);
        }
        publishFileChange(previouslyExisted ? FileChangeEvent.Operation.UPDATED : FileChangeEvent.Operation.CREATED, file, null);
    }

    public boolean fileDeleted(Path file) {
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                publishFileChange(FileChangeEvent.Operation.DELETED, file, null);
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
        publishFileChange(FileChangeEvent.Operation.RENAME, moved, previousFile);
        return moved;
    }

    private void publishFileChange(FileChangeEvent.Operation operation,
                                   Path file,
                                   Path previousFile) {
        Path url = file.toAbsolutePath().normalize();
        Path previousUrl = previousFile == null ? null : previousFile.toAbsolutePath().normalize();
        eventPublisher.publishEvent(new FileChangeEvent(sourcePluginId, operation, url, previousUrl));
    }

    private void createParentDirectories(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
