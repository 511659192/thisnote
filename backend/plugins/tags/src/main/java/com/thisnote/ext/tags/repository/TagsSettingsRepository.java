package com.thisnote.ext.tags.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Repository
public class TagsSettingsRepository {

    private static final int DEFAULT_FONT_SIZE = 15;

    private final FileHelper fileHelper;
    private final Path settingsFile;

    public TagsSettingsRepository(FileHelper fileHelper,
                                  @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("tags");
        this.settingsFile = pluginDir.resolve("settings.json");
    }

    public synchronized String getAiProfileId() {
        return read().aiProfileId();
    }

    public synchronized boolean getAutoEnabled() {
        return read().autoEnabled();
    }

    public synchronized int getFontSize() {
        return read().fontSize();
    }

    public synchronized void update(String aiProfileId,
                                    boolean autoEnabled,
                                    int fontSize) {
        try {
            fileHelper.fileWritten(settingsFile, new StoredSettings(aiProfileId, autoEnabled, fontSize));
            log.info("标签设置已写入：path={}, profileId={}, autoEnabled={}, fontSize={}",
                    settingsFile, aiProfileId, autoEnabled, fontSize);
        } catch (RuntimeException e) {
            log.error("标签设置写入失败：path={}, profileId={}, autoEnabled={}, fontSize={}",
                    settingsFile, aiProfileId, autoEnabled, fontSize, e);
            throw e;
        }
    }

    private StoredSettings read() {
        if (!Files.exists(settingsFile)) {
            return new StoredSettings("", true, DEFAULT_FONT_SIZE);
        }
        try {
            var raw = JSON.parseObject(Files.readString(settingsFile));
            if (raw == null) {
                return new StoredSettings("", true, DEFAULT_FONT_SIZE);
            }
            Integer fontSize = raw.getInteger("fontSize");
            return new StoredSettings(
                    raw.getString("aiProfileId") == null ? "" : raw.getString("aiProfileId"),
                    !Boolean.FALSE.equals(raw.getBoolean("autoEnabled")),
                    fontSize == null ? DEFAULT_FONT_SIZE : fontSize
            );
        } catch (Exception e) {
            log.error("标签设置文件读取失败：{}", settingsFile, e);
            throw new IllegalStateException("标签设置文件无法读取", e);
        }
    }

    private record StoredSettings(String aiProfileId, boolean autoEnabled, int fontSize) {
    }
}