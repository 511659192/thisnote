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

    public synchronized void update(String aiProfileId,
                                    boolean autoEnabled) {
        try {
            fileHelper.fileWritten(settingsFile, new StoredSettings(aiProfileId, autoEnabled));
            log.info("标签设置已写入：path={}, profileId={}, autoEnabled={}",
                    settingsFile, aiProfileId, autoEnabled);
        } catch (RuntimeException e) {
            log.error("标签设置写入失败：path={}, profileId={}, autoEnabled={}",
                    settingsFile, aiProfileId, autoEnabled, e);
            throw e;
        }
    }

    private StoredSettings read() {
        if (!Files.exists(settingsFile)) {
            return new StoredSettings("", true);
        }
        try {
            var raw = JSON.parseObject(Files.readString(settingsFile));
            if (raw == null) {
                return new StoredSettings("", true);
            }
            return new StoredSettings(
                    raw.getString("aiProfileId") == null ? "" : raw.getString("aiProfileId"),
                    !Boolean.FALSE.equals(raw.getBoolean("autoEnabled"))
            );
        } catch (Exception e) {
            log.error("标签设置文件读取失败：{}", settingsFile, e);
            throw new IllegalStateException("标签设置文件无法读取", e);
        }
    }

    private record StoredSettings(String aiProfileId, boolean autoEnabled) {
    }
}
