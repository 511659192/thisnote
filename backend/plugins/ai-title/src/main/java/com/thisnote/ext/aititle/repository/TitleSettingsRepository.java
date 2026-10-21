package com.thisnote.ext.aititle.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnote.ext.aititle.AiTitlePlugin;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Repository
public class TitleSettingsRepository {

    private final FileHelper fileHelper;
    private final Path settingsFile;

    public TitleSettingsRepository(FileHelper fileHelper,
                                   @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize()
                .resolve("notes").resolve("plugins").resolve(AiTitlePlugin.PLUGIN_ID);
        this.settingsFile = pluginDir.resolve("settings.json");
    }

    public synchronized String getAiProfileId() {
        if (!Files.exists(settingsFile)) {
            return "";
        }
        try {
            var raw = JSON.parseObject(Files.readString(settingsFile));
            return raw == null || raw.getString("aiProfileId") == null ? "" : raw.getString("aiProfileId");
        } catch (Exception e) {
            log.error("AI 标题设置文件读取失败：{}", settingsFile, e);
            throw new IllegalStateException("AI 标题设置文件无法读取", e);
        }
    }

    public synchronized void updateAiProfileId(String aiProfileId) {
        try {
            fileHelper.fileWritten(settingsFile, new StoredSettings(aiProfileId));
            log.info("AI 标题设置已写入：path={}, profileId={}", settingsFile, aiProfileId);
        } catch (RuntimeException e) {
            log.error("AI 标题设置写入失败：path={}, profileId={}", settingsFile, aiProfileId, e);
            throw e;
        }
    }

    private record StoredSettings(String aiProfileId) {
    }
}
