package com.thisnode.ext.notes.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
@Slf4j
public class NotePO {

    private int v;
    private String noteId;
    private String title;
    private String bookId;
    private List<String> cardIds = List.of();

    public static NotePO parse(Path filePath) {
        try {
            String json = Files.readString(filePath, StandardCharsets.UTF_8);
            return json.isBlank() ? null : com.alibaba.fastjson2.JSON.parseObject(json, NotePO.class);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            log.warn("元数据无法解析，已跳过：{}", filePath, e);
            return null;
        }
    }

}
