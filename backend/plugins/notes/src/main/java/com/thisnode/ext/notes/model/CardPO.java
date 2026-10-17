package com.thisnode.ext.notes.model;

import com.alibaba.fastjson2.JSON;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@Accessors(chain = true)
public class CardPO {

    public static final String META_PREFIX = "<!-- thisnode:meta ";
    public static final String META_SUFFIX = " -->";

    private CardMeta meta;
    private String content;
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private transient String sourceFileName;

    public CardPO(CardMeta meta, String content) {
        this(meta, content, null);
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public CardPO withSourceFileName(String sourceFileName) {
        this.sourceFileName = sourceFileName;
        return this;
    }

    public static CardMeta parseMeta(Path file) throws IOException {
        try (var reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            return header == null ? null : meta(header);
        }
    }

    public static CardPO parse(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        CardMeta cardMeta = meta(raw);
        String cardContent = cardMeta == null ? raw : content(raw);
        return new CardPO(cardMeta, cardContent).withSourceFileName(file.getFileName().toString());
    }

    private static String headerLine(String fileContent) {
        if (fileContent == null) {
            return "";
        }
        int newline = fileContent.indexOf('\n');
        String first = newline < 0 ? fileContent : fileContent.substring(0, newline);
        return first.strip();
    }

    /**
     * 磁盘上的卡片内容 = meta 注释 + 换行 + 正文。
     */
    public static String compose(CardMeta meta,
                                 String content) {
        String json = JSON.toJSONString(meta).replace("\r", " ").replace("\n", " ");
        return META_PREFIX + json + META_SUFFIX + "\n" + (content == null ? "" : content);
    }

    public static CardMeta meta(String fileContent) {
        String firstLine = headerLine(fileContent);
        if (!firstLine.startsWith(META_PREFIX) || !firstLine.endsWith(META_SUFFIX) || firstLine.length() < META_PREFIX.length() + META_SUFFIX.length()) {
            return null;
        }
        String json = firstLine.substring(META_PREFIX.length(), firstLine.length() - META_SUFFIX.length()).strip();
        try {
            return JSON.parseObject(json, CardMeta.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String content(String fileContent) {
        if (fileContent == null) {
            return "";
        }
        int newline = fileContent.indexOf('\n');
        if (!headerLine(fileContent).startsWith(META_PREFIX)) {
            return fileContent;
        }
        return newline < 0 ? "" : fileContent.substring(newline + 1);
    }

    @Getter
    @Setter
    public static class CardMeta {

        public static final int SCHEMA = 2;

        /**
         * 卡片几何默认值，与旧 note_blocks 表的默认值保持一致。
         */
        public static final double DEFAULT_W = 720;
        public static final double DEFAULT_H = 260;

        private int v = SCHEMA;
        private String cardId;
        private String sourceFileName;
        private String noteId;
        private double x = 0d;
        private double y = 0d;
        private double w = DEFAULT_W;
        private double h = DEFAULT_H;
        private int z = 0;
        private String color;
        private String status;
        private boolean collapsed;
        private Map<String, Object> ext = new HashMap<>();
    }
}
