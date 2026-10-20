package com.thisnote.ext.ai.service;

import com.thisnote.ext.ai.client.AiProviderClient;
import com.thisnote.ext.ai.repository.AiSettingsRepository;
import com.thisnote.plugin.api.event.AiRuntimeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class AiSettingsService {

    public static final String OPENCODE_GO_PROVIDER = "opencode-go";
    public static final int DEFAULT_MAX_TEXT_CHARS = 1_000_000;
    public static final int MIN_MAX_TEXT_CHARS = 1_000;
    public static final int MAX_MAX_TEXT_CHARS = 1_000_000;

    private static final Map<String, Provider> PROVIDERS = Map.of(OPENCODE_GO_PROVIDER, new Provider("deepseek-v4.1-flash"));

    private final AiSettingsRepository settingsRepository;
    private final AiProviderClient providerClient;

    public AiSettingsService(AiSettingsRepository settingsRepository,
                             AiProviderClient providerClient) {
        this.settingsRepository = settingsRepository;
        this.providerClient = providerClient;
    }

    public synchronized Settings settings() {
        AiSettingsRepository.StoredSettings stored = settingsRepository.loadSettings();
        if (stored == null) {
            return defaults();
        }
        if (stored.provider() == null || stored.provider().isBlank()) {
            throw new IllegalStateException("AI 设置缺少模型供应商");
        }
        Provider provider = supportedProvider(stored.provider());
        String model = stored.model() == null || stored.model().isBlank() ? provider.defaultModel() : stored.model().trim();
        return new Settings(provider.name(), model, normalizeMaxTextChars(stored.maxTextChars()));
    }

    public AiRuntimeEvent.Runtime runtime() {
        Settings settings = settings();
        return new AiRuntimeEvent.Runtime(settings.provider(), settings.model(), settingsRepository.apiKey(settings.provider()));
    }

    public SettingsResponse response() {
        Settings settings = settings();
        return new SettingsResponse(settings.provider(), settings.model(), settings.maxTextChars(), !settingsRepository.apiKey(settings.provider()).isBlank());
    }

    public synchronized SettingsResponse save(SettingsUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
        Provider provider = supportedProvider(update.provider());
        String model = update.model() == null ? "" : update.model().trim();
        if (model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        int maxTextChars = update.maxTextChars() == null ? settings().maxTextChars() : requireMaxTextChars(update.maxTextChars());
        Settings next = new Settings(provider.name(), model, maxTextChars);

        Map<String, String> apiKeys = new LinkedHashMap<>(settingsRepository.readApiKeys());
        if (Boolean.TRUE.equals(update.clearApiKey())) {
            apiKeys.remove(provider.name());
        } else if (update.apiKey() != null && !update.apiKey().isBlank()) {
            apiKeys.put(provider.name(), update.apiKey().trim());
        }

        settingsRepository.saveSettings(new AiSettingsRepository.StoredSettings(next.provider(), next.model(), next.maxTextChars()));
        settingsRepository.saveApiKeys(apiKeys);
        return new SettingsResponse(next.provider(), next.model(), next.maxTextChars(), !apiKeys.getOrDefault(next.provider(), "").isBlank());
    }

    public TestResponse test() {
        AiRuntimeEvent.Runtime runtime = runtime();
        if (!runtime.configured()) {
            return new TestResponse(false, "请先配置 Provider、模型和 API key");
        }
        try {
            providerClient.test(runtime);
            return new TestResponse(true, "AI 连接测试成功");
        } catch (Exception e) {
            log.error("AI 连接测试失败", e);
            return new TestResponse(false, "AI 连接测试失败：" + e.getMessage());
        }
    }

    private static Settings defaults() {
        return new Settings(OPENCODE_GO_PROVIDER, PROVIDERS.get(OPENCODE_GO_PROVIDER).defaultModel(), DEFAULT_MAX_TEXT_CHARS);
    }

    private static int normalizeMaxTextChars(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_MAX_TEXT_CHARS;
        }
        return Math.max(MIN_MAX_TEXT_CHARS, Math.min(MAX_MAX_TEXT_CHARS, value));
    }

    private static int requireMaxTextChars(int value) {
        if (value < MIN_MAX_TEXT_CHARS || value > MAX_MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("标签抽取文本上限必须在 1,000 到 1,000,000 字符之间");
        }
        return value;
    }

    private static Provider supportedProvider(String value) {
        Provider provider = value == null ? null : PROVIDERS.get(value.trim().toLowerCase());
        if (provider == null) {
            throw new IllegalArgumentException("当前仅支持 OpenCode Go");
        }
        return new Provider(value.trim().toLowerCase(), provider.defaultModel());
    }

    private record Provider(String name, String defaultModel) {
        private Provider(String defaultModel) {
            this("", defaultModel);
        }
    }

    public record Settings(String provider, String model, int maxTextChars) {
    }

    public record SettingsResponse(String provider, String model, int maxTextChars, boolean keyConfigured) {
    }

    public record SettingsUpdate(String provider, String model, String apiKey, Boolean clearApiKey, Integer maxTextChars) {
    }

    public record TestResponse(boolean success, String message) {
    }
}
