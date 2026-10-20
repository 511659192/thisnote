package com.thisnote.ext.graphify.service;

import com.thisnote.ext.graphify.client.GraphifyAiClient;
import com.thisnote.ext.graphify.repository.GraphifySettingsRepository;
import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class GraphifySettingsService {

    public static final String OPENCODE_GO_PROVIDER = "opencode-go";

    private static final Map<String, Provider> PROVIDERS = Map.of(OPENCODE_GO_PROVIDER, new Provider("deepseek-v4.1-flash", List.of("OPENAI_API_KEY")));
    private static final List<String> ENVIRONMENT_KEY_NAMES_TO_CLEAR = List.of("GEMINI_API_KEY", "GOOGLE_API_KEY", "MOONSHOT_API_KEY", "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY",
                                                                               "OLLAMA_API_KEY");

    private final GraphifySettingsRepository settingsRepository;
    private final GraphifyAiClient aiClient;

    public GraphifySettingsService(GraphifySettingsRepository settingsRepository,
                                   GraphifyAiClient aiClient) {
        this.settingsRepository = settingsRepository;
        this.aiClient = aiClient;
    }

    public Settings settings() {
        String profileId = selectedProfileId();
        String executablePath = settingsRepository.getExecutablePath();
        try {
            AiRuntimeFutureEvent.Runtime runtime = aiClient.runtime(profileId);
            return new Settings(profileId, runtime.provider(), runtime.model(), executablePath);
        } catch (Exception e) {
            return new Settings(profileId, "", "", executablePath);
        }
    }

    public String apiKey(String profileId) {
        return aiClient.runtime(profileId).apiKey();
    }

    public SettingsResponse response() {
        Settings settings = settings();
        boolean keyConfigured = false;
        try {
            keyConfigured = !apiKey(settings.aiProfileId()).isBlank();
        } catch (Exception ignored) {
            // 配置缺失时仍要能打开设置页重新选择。
        }
        return new SettingsResponse(
                settings.aiProfileId(),
                settings.provider(),
                settings.model(),
                settings.executablePath(),
                keyConfigured,
                profiles()
        );
    }

    public synchronized SettingsResponse save(SettingsUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
        String executablePath = update.executablePath() == null ? "" : update.executablePath().trim();
        String profileId = update.aiProfileId() == null || update.aiProfileId().isBlank()
                ? AiProfilesFutureEvent.DEFAULT_PROFILE_ID
                : update.aiProfileId().trim();
        List<AiProfileSummary> available = profiles();
        if (available.isEmpty()) {
            throw new IllegalStateException("AI 核心插件未返回可用的 completion 配置");
        }
        if (available.stream().noneMatch(profile -> profileId.equals(profile.id()))) {
            throw new IllegalArgumentException("AI profile 不存在：" + profileId);
        }
        settingsRepository.update(executablePath, profileId);
        return response();
    }

    public List<AiProfileSummary> profiles() {
        try {
            return aiClient.profiles();
        } catch (Exception e) {
            return List.of();
        }
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

    private String selectedProfileId() {
        String stored = settingsRepository.getAiProfileId();
        return stored == null || stored.isBlank() ? AiProfilesFutureEvent.DEFAULT_PROFILE_ID : stored;
    }

    private static Provider supportedProvider(String value) {
        Provider provider = value == null ? null : PROVIDERS.get(value.trim().toLowerCase());
        if (provider == null) {
            throw new IllegalArgumentException("AI Provider 配置无效");
        }
        return new Provider(value.trim().toLowerCase(), provider.defaultModel(), provider.keyNames());
    }

    private record Provider(String name, String defaultModel, List<String> keyNames) {
        private Provider(String defaultModel,
                         List<String> keyNames) {
            this("", defaultModel, keyNames);
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
                                   List<AiProfileSummary> profiles) {
    }

    public record SettingsUpdate(String executablePath, String aiProfileId) {
    }
}
