package com.thisnote.ext.graphify.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Repository
public class GraphifySettingsRepository {

    private final FileHelper fileHelper;
    private final Path settingsFile;

    public GraphifySettingsRepository(FileHelper fileHelper,
                                      @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("graphify");
        this.settingsFile = pluginDir.resolve("settings.json");
    }

    public synchronized String getExecutablePath() {
        if (!Files.exists(settingsFile)) {
            return "";
        }
        try {
            StoredCliSettings stored = read();
            return stored == null || stored.executablePath() == null ? "" : stored.executablePath();
        } catch (Exception e) {
            throw new IllegalStateException("Graphify 设置文件无法读取", e);
        }
    }

    public synchronized String getAiProfileId() {
        if (!Files.exists(settingsFile)) {
            return "";
        }
        try {
            StoredCliSettings stored = read();
            return stored == null || stored.aiProfileId() == null ? "" : stored.aiProfileId();
        } catch (Exception e) {
            throw new IllegalStateException("Graphify 设置文件无法读取", e);
        }
    }

    public synchronized void update(String executablePath,
                                    String aiProfileId) {
        fileHelper.fileWritten(settingsFile, new StoredCliSettings(executablePath, aiProfileId));
    }

    private StoredCliSettings read() throws IOException {
        var raw = JSON.parseObject(Files.readString(settingsFile));
        return raw == null ? new StoredCliSettings("", "") : new StoredCliSettings(raw.getString("executablePath"), raw.getString("aiProfileId"));
    }

    private record StoredCliSettings(String executablePath, String aiProfileId) {
    }
}
