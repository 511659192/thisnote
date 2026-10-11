package com.thisnode.ext.notes.domain;

import com.alibaba.fastjson2.annotation.JSONField;
import com.thisnode.ext.notes.util.JsonFileUtils;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
@Slf4j
public class NoteMeta {
    private int v;
    private String id;
    private String title;
    private String type;
    private String notebookId;
    @JSONField(alternateNames = {"cardPaths"})
    private List<String> cardFileNames = List.of();
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = System.currentTimeMillis();

    public static NoteMeta parse(Path filePath) {
        if (!Files.isRegularFile(filePath)) {
            return null;
        }

        try {
            NoteMeta note = JsonFileUtils.read(filePath, NoteMeta.class);
            if (note != null && note.getCardFileNames() == null) {
                note.setCardFileNames(List.of());
            }
            return note;
        } catch (IOException | RuntimeException e) {
            log.warn("元数据无法解析，已跳过：{}", filePath, e);
            return null;
        }
    }

}
