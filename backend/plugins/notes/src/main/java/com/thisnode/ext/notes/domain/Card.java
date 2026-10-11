package com.thisnode.ext.notes.domain;

import com.alibaba.fastjson2.JSON;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.experimental.Accessors;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Getter
@Setter
@AllArgsConstructor
@Accessors(chain = true)
public class Card {

    public static final String META_PREFIX = "<!-- thisnode:meta ";
    public static final String META_SUFFIX = " -->";

    private CardMeta meta;
    private String content;

    @SneakyThrows
    public static Card parse(Path file) {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw == null) {
            return null;
        }

        CardMeta cardMeta = CardMeta.metaOf(raw);
        String content = bodyOf(raw);
        return new Card(cardMeta, content);
    }

    private static String firstLineOf(String fileContent) {
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
                                 String contentMd) {
        String json = JSON.toJSONString(meta).replace("\r", " ").replace("\n", " ");
        return META_PREFIX + json + META_SUFFIX + "\n" + (contentMd == null ? "" : contentMd);
    }




    /**
     * 正文 = 文件内容去掉头部注释行（没有注释就是原样）。
     */
    public static String bodyOf(String fileContent) {
        if (fileContent == null) {
            return "";
        }
        int newline = fileContent.indexOf('\n');
        if (!firstLineOf(fileContent).startsWith(META_PREFIX)) {
            return fileContent;
        }
        return newline < 0 ? "" : fileContent.substring(newline + 1);
    }

    @Getter
    @Setter
    @AllArgsConstructor
    public static class CardMeta {

        public static final int SCHEMA = 2;

        /**
         * 卡片几何默认值，与旧 note_blocks 表的默认值保持一致。
         */
        public static final double DEFAULT_W = 720;
        public static final double DEFAULT_H = 260;

        private int v;
        private String id;
        private String title;
        private double x;
        private double y;
        private double w;
        private double h;
        private int z;
        private String color;
        private String status;
        private boolean collapsed;
        private Map<String, Object> ext;
        private long createdAt;
        private long updatedAt;

        public static CardMeta newCardMeta(String id,
                                           double x,
                                           double y,
                                           String title,
                                           String color,
                                           String status,
                                           long now) {
            return new CardMeta(SCHEMA, id, title, x, y, DEFAULT_W, DEFAULT_H, 0, color, status, false, Map.of(), now, now);
        }

        public static CardMeta metaOf(String fileContent) {
            String firstLine = firstLineOf(fileContent);
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
    }
}