package com.thisnode.ext.notes.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 落盘原语：直接覆盖目标文件，保留已有文件的创建时间。
 *
 * <p>笔记 meta、卡片正文、标签台账都通过这里写入；写入中断可能留下不完整内容。
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class JsonFileUtils {

    public static void writeText(Path file,
                                 String text) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8,
                              StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("写入失败 " + file, e);
        }
    }

    public static void write(Path file,
                             Object value) {
        writeText(file, JSON.toJSONString(value, JSONWriter.Feature.PrettyFormat));
    }

    /**
     * 文件不存在或空白给 {@code null}；JSON 坏掉抛异常，由调用方决定怎么降级。
     */
    public static <T> T read(Path file,
                             Class<T> type) throws IOException {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.isBlank() ? null : JSON.parseObject(text, type);
    }
}
