package com.thisnote.ext.ai.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class AiSettingsRepository {

    public static final String DEFAULT_MODEL = "deepseek-v4.1-flash";
    public static final String DEFAULT_ENDPOINT = "https://opencode.ai/inference/go/openai/v1/chat/completions";
    public static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    public static final String DEEPSEEK_ENDPOINT = "https://api.deepseek.com/chat/completions";

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

    public synchronized List<StoredProfile> getProfiles() {
        if (!Files.exists(settingsFile)) {
            return List.of();
        }
        try {
            JSONObject raw = JSON.parseObject(Files.readString(settingsFile));
            if (raw == null) {
                throw new IllegalStateException("AI 设置文件内容为空");
            }
            JSONArray profiles = raw.getJSONArray("profiles");
            if (profiles == null) {
                return List.of();
            }
            return profiles.stream()
                    .filter(JSONObject.class::isInstance)
                    .map(JSONObject.class::cast)
                    .map(profile -> new StoredProfile(profile.getString("id"), profile.getString("label"), profile.getString("provider"), profile.getString("model"), profile.getString("endpoint"),
                                                      profile.getInteger("maxTextChars")))
                    .toList();
        } catch (Exception e) {
            throw new IllegalStateException("AI 设置文件无法读取", e);
        }
    }

    public synchronized void updateProfiles(List<StoredProfile> profiles) {
        fileHelper.fileWritten(settingsFile, new StoredSettings(profiles));
    }

    public synchronized String getApiKey(String profileId) {
        return getAllApiKeys().getOrDefault(profileId, "");
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
            Map<String, String> result = stored.keySet().stream().filter(profileId -> {
                String value = stored.getString(profileId);
                return value != null && !value.isBlank();
            }).collect(Collectors.toMap(profileId -> profileId, stored::getString, (first, second) -> first, LinkedHashMap::new));
            return result;
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
        fileHelper.fileWritten(credentialsFile, new StoredCredentials(2, apiKeys));
    }

    public record StoredProfile(String id, String label, String provider, String model, String endpoint, Integer maxTextChars) {

        public StoredProfile {
            id = id == null ? "" : id.trim();
            label = label == null ? "" : label.trim();
            provider = provider == null ? "" : provider.trim();
            model = model == null ? "" : model.trim();
            endpoint = endpoint == null ? "" : endpoint.trim();
        }
    }

    private record StoredSettings(List<StoredProfile> profiles) {
    }

    private record StoredCredentials(int version,
                                     Map<String, String> apiKeys) {
    }
}
