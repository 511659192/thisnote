package com.thisnote.plugin.api.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.fastjson2.TypeReference;
import com.thisnote.plugin.api.event.FileChangeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Executes persistent file mutations and publishes their plugin-scoped change events.
 */
@Slf4j
@RequiredArgsConstructor
public final class FileHelper {

    private final ApplicationEventPublisher eventPublisher;
    private final String sourcePluginId;

    /**
     * 5 秒缓冲窗口：窗口内完全相同的事件合并为一条，窗口结束时才送达消费者。
     */
    private static final long FILE_EVENT_BUFFER_SECONDS = 5;
    private final ConcurrentHashMap<String, Boolean> bufferedFileEvents = new ConcurrentHashMap<>();

    public void fileWritten(Path file,
                            Object content) {
        fileWritten(file, JSON.toJSONString(content, JSONWriter.Feature.PrettyFormat));
    }

    public void fileWritten(Path file,
                            String content) {
        boolean previouslyExisted = Files.exists(file);
        try {
            createParentDirectories(file);
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
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
            throw new UncheckedIOException("保存图片附件失败", e);
        }
        publishFileChange(previouslyExisted ? FileChangeEvent.Operation.UPDATED : FileChangeEvent.Operation.CREATED, destination, null);
        log.info("保存图片附件 {}", destination.getFileName());
    }

    /**
     * Copies and closes the stream, writing directly to the destination before publishing its change event.
     */
    public void fileWritten(Path file,
                            InputStream content,
                            long modifiedAtMillis) {
        boolean previouslyExisted = Files.exists(file);
        try (InputStream input = content) {
            createParentDirectories(file);
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
            if (modifiedAtMillis > 0) {
                Files.setLastModifiedTime(file, FileTime.fromMillis(modifiedAtMillis));
            }
        } catch (IOException e) {
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
            return false;
        }
    }

    public Path rename(Path previousFile,
                       Path file,
                       CopyOption... options) {
        Path moved;
        try {
            moved = Files.move(previousFile, file, options);
        } catch (IOException e) {
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
        String key = operation + ":" + url + (previousUrl == null ? "" : ":" + previousUrl);
        FileChangeEvent event = new FileChangeEvent(sourcePluginId, operation, url, previousUrl);
        // 首条事件占用 5 秒缓冲位并调度送达；期间完全相同的重复事件不再入池，由这条统一代表。
        if (bufferedFileEvents.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        CompletableFuture.delayedExecutor(FILE_EVENT_BUFFER_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (bufferedFileEvents.remove(key) == null) {
                return;
            }
            eventPublisher.publishEvent(event);
        });
    }

    private void createParentDirectories(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    /**
     * 读取整份 JSON map 索引（如 noteId → PO 的单文件全量索引）。
     * 文件缺失/空白/解析为 null 一律按「索引损坏」处理：抛出并保留现场等人工介入，不静默重建。
     */
    public static <T> Map<String, T> readMapIndex(Path file,
                                                  TypeReference<LinkedHashMap<String, T>> type,
                                                  String label) {
        try {
            Map<String, T> map = JSON.parseObject(Files.readString(file, StandardCharsets.UTF_8), type);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            throw new IllegalStateException(label + "索引无法解析 " + file, e);
        }
    }

    /**
     * 读取整份 JSON map；文件缺失视为「还没有数据」返回空 map，
     * 其余读取/解析失败抛出（保留原文件，不静默重建）。
     */
    public static <T> Map<String, T> readMapOrEmpty(Path file,
                                                    TypeReference<LinkedHashMap<String, T>> type,
                                                    String label) {
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return new LinkedHashMap<>();
        } catch (IOException e) {
            throw new IllegalStateException(label + "文件无法读取 " + file, e);
        }
        try {
            Map<String, T> map = json.isBlank() ? null : JSON.parseObject(json, type);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            throw new IllegalStateException(label + "文件无法读取 " + file, e);
        }
    }
}
