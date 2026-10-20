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

    public Settings settings() {
        String profileId = selectedProfileId();
        String executablePath = settingsRepository.getExecutablePath();
        try {
            return resolveSettings();
        } catch (Exception e) {
            log.warn("Graphify 运行时配置不可用：{}", e.getMessage());
            return new Settings(profileId, "", "", executablePath);
        }
    }

    public String apiKey(String profileId) {
        return aiClient.runtime(profileId).apiKey();
    }

    public SettingsResponse response() {
        Settings settings;
        String aiError = "";
        try {
            settings = resolveSettings();
        } catch (Exception e) {
            String profileId = selectedProfileId();
            settings = new Settings(profileId, "", "", settingsRepository.getExecutablePath());
            aiError = e.getMessage() == null ? "AI 配置不可用" : e.getMessage();
            log.warn("读取 Graphify AI 运行时配置失败：{}", aiError);
        }
        boolean keyConfigured = false;
        try {
            keyConfigured = !apiKey(settings.aiProfileId()).isBlank();
        } catch (Exception e) {
            if (aiError.isBlank()) {
                aiError = e.getMessage() == null ? "AI 配置不可用" : e.getMessage();
            }
        }
        List<AiProfileSummary> profiles;
        String profilesError = "";
        try {
            profiles = aiClient.profiles();
        } catch (Exception e) {
            profiles = List.of();
            profilesError = e.getMessage() == null ? "AI profile 列表不可用" : e.getMessage();
            log.warn("读取 Graphify AI profile 列表失败：{}", profilesError);
        }
        return new SettingsResponse(
                settings.aiProfileId(),
                settings.provider(),
                settings.model(),
                settings.executablePath(),
                keyConfigured,
                profiles,
                aiError,
                profilesError
        );
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
        List<AiProfileSummary> available = profiles();
        if (available.isEmpty()) {
            throw new IllegalStateException("AI 核心插件未返回可用的 completion 配置");
        }
        if (available.stream().noneMatch(profile -> profileId.equals(profile.id()))) {
            throw new IllegalArgumentException("AI profile 不存在：" + profileId);
        }
        settingsRepository.update(executablePath, profileId);
        log.info("Graphify 设置保存成功：aiProfileId={}, executablePath={}", profileId, executablePath);
        return response();
    }

    public List<AiProfileSummary> profiles() {
        try {
            List<AiProfileSummary> profiles = aiClient.profiles();
            log.debug("Graphify AI profile 列表读取成功：count={}", profiles.size());
            return profiles;
        } catch (Exception e) {
            log.warn("Graphify AI profile 列表读取失败：{}", e.getMessage());
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
        return stored == null ? "" : stored;
    }

    private Settings resolveSettings() {
        String profileId = selectedProfileId();
        if (profileId.isBlank()) {
            throw new IllegalStateException("请先选择 AI profile");
        }
        AiRuntimeFutureEvent.Runtime runtime = aiClient.runtime(profileId);
        return new Settings(
                profileId,
                runtime.provider(),
                runtime.model(),
                settingsRepository.getExecutablePath()
        );
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
