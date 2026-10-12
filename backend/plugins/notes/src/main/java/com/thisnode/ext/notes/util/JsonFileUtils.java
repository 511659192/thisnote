package com.thisnode.ext.notes.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 落盘原语：先写临时文件再原子替换 —— 读的人永远不会看到半截文件。
 *
 * <p>笔记 meta、卡片正文、标签台账都要写文件，但它们的仓储不该为了借这个方法而互相依赖，
 * 所以"怎么写才安全"只在这里有一份实现。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JsonFileUtils {

    public static void writeText(Path file, String text) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, text, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            move(tmp, file);
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败 " + file, e);
        }
    }

    public static void write(Path file, Object value) {
        writeText(file, JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat));
    }

    /** 文件不存在或空白给 {@code null}；JSON 坏掉抛异常，由调用方决定怎么降级。 */
    public static <T> T read(Path file, Class<T> type) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.isBlank() ? null : JSON.parseObject(text, type);
    }

    public static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
