package com.thisnote.ext.notes.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Repository
public class NotesSettingsRepository {

    private static final int DEFAULT_FONT_SIZE = 15;

    private final FileHelper fileHelper;
    private final Path settingsFile;

    public NotesSettingsRepository(FileHelper fileHelper,
                                   @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("notes");
        this.settingsFile = pluginDir.resolve("settings.json");
    }

    public synchronized int getFontSize() {
        if (!Files.exists(settingsFile)) {
            return DEFAULT_FONT_SIZE;
        }
        try {
            var raw = JSON.parseObject(Files.readString(settingsFile));
            if (raw == null) {
                return DEFAULT_FONT_SIZE;
            }
            Integer fontSize = raw.getInteger("fontSize");
            return fontSize == null ? DEFAULT_FONT_SIZE : fontSize;
        } catch (Exception e) {
            log.error("笔记设置文件读取失败：path={}", settingsFile, e);
            throw new IllegalStateException("笔记设置文件无法读取", e);
        }
    }

    public synchronized void updateFontSize(int fontSize) {
        try {
            fileHelper.fileWritten(settingsFile, new StoredSettings(fontSize));
            log.info("笔记设置已写入：path={}, fontSize={}", settingsFile, fontSize);
        } catch (RuntimeException e) {
            log.error("笔记设置写入失败：path={}, fontSize={}", settingsFile, fontSize, e);
            throw e;
        }
    }

    private record StoredSettings(int fontSize) {
    }
}