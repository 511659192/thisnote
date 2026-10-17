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
        return new CardPO(null, Files.readString(file, StandardCharsets.UTF_8)).withSourceFileName(file.getFileName().toString());
    }

    @Getter
    @Setter
    public static class CardMeta {

        /**
         * 卡片几何默认值，与旧 note_blocks 表的默认值保持一致。
         */
        public static final double DEFAULT_W = 720;
        public static final double DEFAULT_H = 260;

        private String cardId;
        private String fileName;
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
