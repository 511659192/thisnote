package com.thisnode.ext.notes.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.IOException;
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

    /**
     * 旧格式元数据注释的前缀/后缀：元数据现在存 {@code .card-index.json}，
     * 读到旧卡片的首行时把它剥掉（旧卡片不做历史迁移）。
     */
    private static final String LEGACY_META_PREFIX = "<!-- thisnode:meta ";
    private static final String LEGACY_META_SUFFIX = " -->";

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

    /**
     * 读取卡片正文。元数据由 {@code CardRepository} 的卡片索引提供，不在文件里。
     */
    public static CardPO parse(Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        return new CardPO(null, stripLegacyHeader(raw)).withSourceFileName(file.getFileName().toString());
    }

    /**
     * 兼容旧格式：首行如果是 {@code <!-- thisnode:meta ... -->} 就剥掉，只留正文。
     */
    private static String stripLegacyHeader(String fileContent) {
        if (fileContent == null) {
            return "";
        }
        int newline = fileContent.indexOf('\n');
        String firstLine = (newline < 0 ? fileContent : fileContent.substring(0, newline)).strip();
        if (!firstLine.startsWith(LEGACY_META_PREFIX) || !firstLine.endsWith(LEGACY_META_SUFFIX)) {
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
