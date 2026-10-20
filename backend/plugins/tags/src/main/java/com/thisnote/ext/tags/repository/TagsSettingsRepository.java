package com.thisnote.ext.tags.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;

@Repository
public class TagsSettingsRepository {

    private final FileHelper fileHelper;
    private final Path settingsFile;

    public TagsSettingsRepository(FileHelper fileHelper,
                                  @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize()
                .resolve("notes").resolve("plugins").resolve("tags");
        this.settingsFile = pluginDir.resolve("settings.json");
    }

    public synchronized String getAiProfileId() {
        if (!Files.exists(settingsFile)) {
            return "";
        }
        try {
            var raw = JSON.parseObject(Files.readString(settingsFile));
            return raw == null || raw.getString("aiProfileId") == null
                    ? ""
                    : raw.getString("aiProfileId");
        } catch (Exception e) {
            throw new IllegalStateException("标签设置文件无法读取", e);
        }
    }

    public synchronized void updateAiProfileId(String aiProfileId) {
        fileHelper.fileWritten(settingsFile, new StoredSettings(aiProfileId));
    }

    private record StoredSettings(String aiProfileId) {
    }
}
