package com.thisnote.ext.ai.repository;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.util.FileHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class AiSettingsRepository {

    public static final String DEFAULT_PROFILE_ID = "default-chat";
    public static final String DEFAULT_PROVIDER = "opencode-go";
    public static final String DEFAULT_MODEL = "deepseek-v4.1-flash";
    public static final String DEFAULT_ENDPOINT = "https://opencode.ai/inference/go/openai/v1/chat/completions";

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
            return List.of(defaultProfile());
        }
        try {
            JSONObject raw = JSON.parseObject(Files.readString(settingsFile));
            if (raw == null) {
                throw new IllegalStateException("AI 设置文件内容为空");
            }
            JSONArray profiles = raw.getJSONArray("profiles");
            if (profiles == null) {
                StoredProfile migrated = migrateLegacy(raw);
                updateProfiles(List.of(migrated));
                return List.of(migrated);
            }
            List<StoredProfile> result = new ArrayList<>();
            for (Object item : profiles) {
                if (!(item instanceof JSONObject profile)) {
                    continue;
                }
                result.add(new StoredProfile(
                        profile.getString("id"),
                        profile.getString("label"),
                        profile.getString("provider"),
                        profile.getString("model"),
                        profile.getString("endpoint"),
                        profile.getInteger("maxTextChars")
                ));
            }
            return List.copyOf(result);
        } catch (Exception e) {
            throw new IllegalStateException("AI 设置文件无法读取", e);
        }
    }

    public synchronized void updateProfiles(List<StoredProfile> profiles) {
        JSONObject stored = new JSONObject();
        JSONArray items = new JSONArray();
        for (StoredProfile profile : profiles) {
            JSONObject item = new JSONObject();
            item.put("id", profile.id());
            item.put("label", profile.label());
            item.put("provider", profile.provider());
            item.put("model", profile.model());
            item.put("endpoint", profile.endpoint());
            if (profile.maxTextChars() != null) {
                item.put("maxTextChars", profile.maxTextChars());
            }
            items.add(item);
        }
        stored.put("profiles", items);
        fileHelper.fileWritten(settingsFile, stored);
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
            if (credentials.getIntValue("version") < 2) {
                String legacyKey = result.remove(DEFAULT_PROVIDER);
                if (legacyKey != null && !result.containsKey(DEFAULT_PROFILE_ID)) {
                    result.put(DEFAULT_PROFILE_ID, legacyKey);
                }
                updateApiKeys(result);
            }
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
        JSONObject credentials = new JSONObject();
        credentials.put("version", 2);
        JSONObject storedKeys = new JSONObject();
        for (Map.Entry<String, String> entry : apiKeys.entrySet()) {
            storedKeys.put(entry.getKey(), entry.getValue());
        }
        credentials.put("apiKeys", storedKeys);
        fileHelper.fileWritten(credentialsFile, credentials);
    }

    private static StoredProfile migrateLegacy(JSONObject raw) {
        String provider = raw.getString("provider");
        String model = raw.getString("model");
        Integer maxTextChars = raw.getInteger("maxTextChars");
        String normalizedProvider = provider == null || provider.isBlank() ? DEFAULT_PROVIDER : provider.trim();
        String normalizedModel = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        String endpoint = DEFAULT_PROVIDER.equals(normalizedProvider) ? DEFAULT_ENDPOINT : "";
        return new StoredProfile(
                DEFAULT_PROFILE_ID,
                "默认对话",
                normalizedProvider,
                normalizedModel,
                endpoint,
                maxTextChars
        );
    }

    private static StoredProfile defaultProfile() {
        return new StoredProfile(
                DEFAULT_PROFILE_ID,
                "默认对话",
                DEFAULT_PROVIDER,
                DEFAULT_MODEL,
                DEFAULT_ENDPOINT,
                1_000_000
        );
    }

    public record StoredProfile(String id,
                                String label,
                                String provider,
                                String model,
                                String endpoint,
                                Integer maxTextChars) {

        public StoredProfile {
            id = id == null ? "" : id.trim();
            label = label == null ? "" : label.trim();
            provider = provider == null ? "" : provider.trim();
            model = model == null ? "" : model.trim();
            endpoint = endpoint == null ? "" : endpoint.trim();
        }
    }
}
