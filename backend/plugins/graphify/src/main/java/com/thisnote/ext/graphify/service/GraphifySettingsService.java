package com.thisnote.ext.graphify.service;

import com.thisnote.ext.graphify.client.GraphifyAiClient;
import com.thisnote.ext.graphify.repository.GraphifySettingsRepository;
import com.thisnote.plugin.api.event.AiRuntimeEvent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class GraphifySettingsService {

    public static final String OPENCODE_GO_PROVIDER = "opencode-go";

    private static final Map<String, Provider> PROVIDERS = Map.of(
            OPENCODE_GO_PROVIDER, new Provider("glm-5.3-flash", List.of("OPENAI_API_KEY"))
    );
    private static final List<String> ENVIRONMENT_KEY_NAMES_TO_CLEAR = List.of(
            "GEMINI_API_KEY", "GOOGLE_API_KEY", "MOONSHOT_API_KEY",
            "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "DEEPSEEK_API_KEY", "OLLAMA_API_KEY"
    );

    private final GraphifySettingsRepository settingsRepository;
    private final GraphifyAiClient aiClient;

    public GraphifySettingsService(GraphifySettingsRepository settingsRepository,
                                   GraphifyAiClient aiClient) {
        this.settingsRepository = settingsRepository;
        this.aiClient = aiClient;
    }

    public Settings settings() {
        AiRuntimeEvent.Runtime runtime = aiClient.runtime();
        return new Settings(runtime.provider(), runtime.model(), settingsRepository.executablePath());
    }

    public String apiKey(String provider) {
        supportedProvider(provider);
        return aiClient.runtime().apiKey();
    }

    public SettingsResponse response() {
        Settings settings = settings();
        return new SettingsResponse(settings.provider(), settings.model(), settings.executablePath(),
                !apiKey(settings.provider()).isBlank());
    }

    public synchronized SettingsResponse save(SettingsUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
        String executablePath = update.executablePath() == null ? "" : update.executablePath().trim();
        settingsRepository.saveExecutablePath(executablePath);
        return response();
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

    private static Provider supportedProvider(String value) {
        Provider provider = value == null ? null : PROVIDERS.get(value.trim().toLowerCase());
        if (provider == null) {
            throw new IllegalArgumentException("AI Provider 配置无效");
        }
        return new Provider(value.trim().toLowerCase(), provider.defaultModel(), provider.keyNames());
    }

    private record Provider(String name,
                            String defaultModel,
                            List<String> keyNames) {
        private Provider(String defaultModel,
                         List<String> keyNames) {
            this("", defaultModel, keyNames);
        }
    }

    public record Settings(String provider,
                           String model,
                           String executablePath) {
    }

    public record SettingsResponse(String provider,
                                   String model,
                                   String executablePath,
                                   boolean keyConfigured) {
    }

    public record SettingsUpdate(String executablePath) {
    }
}
