package com.thisnote.ext.graphify.service;

import com.thisnote.ext.graphify.client.GraphifyAiClient;
import com.thisnote.ext.graphify.repository.GraphifySettingsRepository;
import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 把选中的 AI profile 解析为 Graphify CLI 使用的 provider/model/key，
 * profile 本身仍由 AI 插件统一存储。
 */
@Service
@Slf4j
public class GraphifySettingsService {

    public static final String OPENCODE_GO_PROVIDER = "opencode-go";

    private static final Map<String, Provider> PROVIDERS = Map.of(OPENCODE_GO_PROVIDER, new Provider(List.of("OPENAI_API_KEY")));
    private static final List<String> ENVIRONMENT_KEY_NAMES_TO_CLEAR = List.of("GEMINI_API_KEY", "GOOGLE_API_KEY", "MOONSHOT_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY",
                                                                               "OLLAMA_API_KEY");

    private final GraphifySettingsRepository settingsRepository;
    private final GraphifyAiClient aiClient;

    public GraphifySettingsService(GraphifySettingsRepository settingsRepository,
                                   GraphifyAiClient aiClient) {
        this.settingsRepository = settingsRepository;
        this.aiClient = aiClient;
    }

    public SettingsResponse response() {
        return response(loadProfiles());
    }

    private SettingsResponse response(ProfilesResult profiles) {
        String profileId = selectedProfileId();
        Settings settings = new Settings(profileId, "", "", settingsRepository.getExecutablePath());
        boolean keyConfigured = false;
        String aiError = "";
        try {
            ResolvedSettings resolved = resolve();
            settings = resolved.settings();
            keyConfigured = !resolved.apiKey().isBlank();
        } catch (Exception e) {
            aiError = e.getMessage() == null ? "AI 配置不可用" : e.getMessage();
            log.warn("读取 Graphify AI 运行时配置失败：{}", aiError);
        }
        return new SettingsResponse(
                settings.aiProfileId(),
                settings.provider(),
                settings.model(),
                settings.executablePath(),
                keyConfigured,
                profiles.profiles(),
                aiError,
                profiles.error()
        );
    }

    private ProfilesResult loadProfiles() {
        List<AiProfileSummary> profiles;
        String profilesError = "";
        try {
            profiles = aiClient.profiles();
        } catch (Exception e) {
            profiles = List.of();
            profilesError = e.getMessage() == null ? "AI profile 列表不可用" : e.getMessage();
            log.warn("读取 Graphify AI profile 列表失败：{}", profilesError);
        }
        return new ProfilesResult(profiles, profilesError);
    }

    public synchronized SettingsResponse save(SettingsUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
        String executablePath = update.executablePath() == null ? "" : update.executablePath().trim();
        if (update.aiProfileId() == null || update.aiProfileId().isBlank()) {
            throw new IllegalArgumentException("请选择 AI profile");
        }
        String profileId = update.aiProfileId().trim();
        ProfilesResult profiles = loadProfiles();
        List<AiProfileSummary> available = profiles.profiles();
        if (available.isEmpty()) {
            throw new IllegalStateException("AI 核心插件未返回可用的 completion 配置");
        }
        if (available.stream().noneMatch(profile -> profileId.equals(profile.id()))) {
            throw new IllegalArgumentException("AI profile 不存在：" + profileId);
        }
        settingsRepository.update(executablePath, profileId);
        log.info("Graphify 设置保存成功：aiProfileId={}, executablePath={}", profileId, executablePath);
        return response(profiles);
    }

    public static List<String> environmentKeyNamesToClear() {
        return ENVIRONMENT_KEY_NAMES_TO_CLEAR;
    }

    public static List<String> environmentKeyNames(String provider) {
        return supportedProvider(provider).keyNames();
    }

    public static String graphifyBackend(String provider) {
        String name = supportedProvider(provider).name();
        return OPENCODE_GO_PROVIDER.equals(name) ? "openai" : name;
    }

    public String selectedProfileId() {
        String stored = settingsRepository.getAiProfileId();
        return stored == null ? "" : stored;
    }

    ResolvedSettings resolve() {
        String profileId = selectedProfileId();
        if (profileId.isBlank()) {
            throw new IllegalStateException("请先选择 AI profile");
        }
        AiRuntimeFutureEvent.Runtime runtime = aiClient.runtime(profileId);
        String apiKey = runtime.apiKey();
        Settings settings = new Settings(
                profileId,
                runtime.provider(),
                runtime.model(),
                settingsRepository.getExecutablePath()
        );
        return new ResolvedSettings(settings, apiKey);
    }

    private static Provider supportedProvider(String value) {
        Provider provider = value == null ? null : PROVIDERS.get(value.trim().toLowerCase());
        if (provider == null) {
            throw new IllegalArgumentException("AI Provider 配置无效");
        }
        return new Provider(value.trim().toLowerCase(), provider.keyNames());
    }

    private record Provider(String name, List<String> keyNames) {
        private Provider(List<String> keyNames) {
            this("", keyNames);
        }
    }

    private record ProfilesResult(List<AiProfileSummary> profiles, String error) {
    }

    record ResolvedSettings(Settings settings, String apiKey) {

        String requireApiKey() {
            if (apiKey.isBlank()) {
                throw new IllegalStateException("请先在 AI 能力插件中为所选 profile 配置 API key");
            }
            return apiKey;
        }
    }

    public record Settings(String aiProfileId,
                           String provider,
                           String model,
                           String executablePath) {
    }

    public record SettingsResponse(String aiProfileId,
                                   String provider,
                                   String model,
                                   String executablePath,
                                   boolean keyConfigured,
                                   List<AiProfileSummary> profiles,
                                   String aiError,
                                   String profilesError) {
    }

    public record SettingsUpdate(String executablePath, String aiProfileId) {
    }
}
