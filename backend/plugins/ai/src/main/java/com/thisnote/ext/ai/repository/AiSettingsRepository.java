package com.thisnote.ext.ai.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class AiSettingsRepository {

    private final FileHelper fileHelper;
    private final Path settingsFile;
    private final Path credentialsFile;

    public AiSettingsRepository(FileHelper fileHelper,
                                @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.fileHelper = fileHelper;
        Path pluginDir = Path.of(dataRoot).toAbsolutePath().normalize().resolve("notes").resolve("plugins").resolve("ai");
        this.settingsFile = pluginDir.resolve("settings.json");
        this.credentialsFile = pluginDir.resolve(".credentials.json");
    }

    public synchronized StoredSettings getSettings() {
        if (!Files.exists(settingsFile)) {
            return null;
        }
        try {
            StoredSettings stored = JSON.parseObject(Files.readString(settingsFile), StoredSettings.class);
            if (stored == null) {
                throw new IllegalStateException("AI 设置文件内容为空");
            }
            return stored;
        } catch (Exception e) {
            throw new IllegalStateException("AI 设置文件无法读取", e);
        }
    }

    public synchronized void updateSettings(StoredSettings settings) {
        fileHelper.fileWritten(settingsFile, settings);
    }

    public synchronized String getApiKey(String provider) {
        return getAllApiKeys().getOrDefault(provider, "");
    }

    public synchronized Map<String, String> getAllApiKeys() {
        if (!Files.exists(credentialsFile)) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject credentials = JSON.parseObject(Files.readString(credentialsFile));
            JSONObject stored = credentials == null ? null : credentials.getJSONObject("apiKeys");
            if (stored == null) {
                return new LinkedHashMap<>();
            }
            return stored.keySet().stream().filter(provider -> {
                String value = stored.getString(provider);
                return value != null && !value.isBlank();
            }).collect(Collectors.toMap(provider -> provider, stored::getString, (first, second) -> first, LinkedHashMap::new));
        } catch (Exception e) {
            throw new IllegalStateException("AI 凭据文件无法读取", e);
        }
    }

    public synchronized void updateApiKeys(Map<String, String> apiKeys) {
        if (apiKeys.isEmpty()) {
            if (!fileHelper.fileDeleted(credentialsFile) && Files.exists(credentialsFile)) {
                throw new IllegalStateException("AI 凭据文件无法删除");
            }
            return;
        }
        fileHelper.fileWritten(credentialsFile, new Credentials(apiKeys));
    }

    private record Credentials(Map<String, String> apiKeys) {
        private Credentials {
            apiKeys = new LinkedHashMap<>(apiKeys);
        }
    }

    public record StoredSettings(String provider, String model, Integer maxTextChars) {
    }
}
