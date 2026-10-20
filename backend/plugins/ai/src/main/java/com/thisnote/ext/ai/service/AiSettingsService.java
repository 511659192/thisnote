package com.thisnote.ext.ai.service;

import com.thisnote.ext.ai.client.AiProviderClient;
import com.thisnote.ext.ai.repository.AiSettingsRepository;
import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class AiSettingsService {

    public static final String OPENCODE_GO_PROVIDER = "opencode-go";
    public static final int DEFAULT_MAX_TEXT_CHARS = 1_000_000;
    public static final int MIN_MAX_TEXT_CHARS = 1_000;
    public static final int MAX_MAX_TEXT_CHARS = 1_000_000;

    private static final Map<String, Provider> PROVIDERS = Map.of(OPENCODE_GO_PROVIDER, new Provider(AiSettingsRepository.DEFAULT_MODEL, AiSettingsRepository.DEFAULT_ENDPOINT));
    private static final Map<String, String> PROVIDER_LABELS = Map.of(OPENCODE_GO_PROVIDER, "OpenCode Go");

    private final AiSettingsRepository settingsRepository;
    private final AiProviderClient providerClient;

    public AiSettingsService(AiSettingsRepository settingsRepository,
                             AiProviderClient providerClient) {
        this.settingsRepository = settingsRepository;
        this.providerClient = providerClient;
    }

    public synchronized List<Profile> profiles() {
        Map<String, String> apiKeys = settingsRepository.getAllApiKeys();
        return settingsRepository.getProfiles().stream().map(profile -> toProfile(profile, apiKeys)).toList();
    }

    public synchronized List<AiProfileSummary> summaries() {
        Map<String, String> apiKeys = settingsRepository.getAllApiKeys();
        return settingsRepository.getProfiles()
                .stream()
                .map(profile -> new AiProfileSummary(profile.id(), profile.label(), profile.provider(), profile.model(), !apiKeys.getOrDefault(profile.id(), "").isBlank()))
                .toList();
    }

    public AiRuntimeFutureEvent.Runtime runtime(String profileId) {
        AiSettingsRepository.StoredProfile profile = requireProfile(profileId);
        return runtime(profile);
    }

    public synchronized SettingsResponse response() {
        return new SettingsResponse(profiles(), providerDefaults());
    }

    public synchronized Profile save(ProfileUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
        String id = update.id() == null ? "" : update.id().trim();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("配置 ID 只能包含字母、数字、点、下划线和连字符");
        }

        List<AiSettingsRepository.StoredProfile> stored = new ArrayList<>(settingsRepository.getProfiles());
        int index = -1;
        for (int i = 0; i < stored.size(); i++) {
            if (id.equals(stored.get(i).id())) {
                index = i;
                break;
            }
        }
        AiSettingsRepository.StoredProfile existing = index < 0 ? null : stored.get(index);
        String providerName = blank(update.provider()) ? (existing == null ? OPENCODE_GO_PROVIDER : existing.provider()) : update.provider().trim().toLowerCase();
        Provider provider = supportedProvider(providerName);
        String model = blank(update.model()) ? (existing == null || existing.model().isBlank() ? provider.defaultModel() : existing.model()) : update.model().trim();
        if (model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        String endpoint = requireEndpoint(blank(update.endpoint()) ? (existing == null || existing.endpoint().isBlank() ? provider.defaultEndpoint() : existing.endpoint()) : update.endpoint().trim());
        Integer maxTextChars = update.maxTextChars() != null ? requireMaxTextChars(
                update.maxTextChars()) : (existing == null || existing.maxTextChars() == null ? DEFAULT_MAX_TEXT_CHARS : normalizeMaxTextChars(existing.maxTextChars()));
        String label = blank(update.label()) ? id : update.label().trim();
        AiSettingsRepository.StoredProfile next = new AiSettingsRepository.StoredProfile(id, label, providerName, model, endpoint, maxTextChars);
        if (index < 0) {
            stored.add(next);
        } else {
            stored.set(index, next);
        }

        Map<String, String> apiKeys = new LinkedHashMap<>(settingsRepository.getAllApiKeys());
        if (Boolean.TRUE.equals(update.clearApiKey())) {
            apiKeys.remove(id);
        } else if (update.apiKey() != null && !update.apiKey().isBlank()) {
            apiKeys.put(id, update.apiKey().trim());
        }

        settingsRepository.updateProfiles(stored);
        settingsRepository.updateApiKeys(apiKeys);
        log.info("AI profile 已保存：id={}, provider={}, model={}, keyConfigured={}", id, providerName, model, !apiKeys.getOrDefault(id, "").isBlank());
        return toProfile(next, apiKeys);
    }

    public synchronized void delete(String profileId) {
        AiSettingsRepository.StoredProfile profile = requireProfile(profileId);
        List<AiSettingsRepository.StoredProfile> stored = new ArrayList<>(settingsRepository.getProfiles());
        stored.removeIf(item -> item.id().equals(profile.id()));
        Map<String, String> apiKeys = new LinkedHashMap<>(settingsRepository.getAllApiKeys());
        apiKeys.remove(profile.id());
        settingsRepository.updateProfiles(stored);
        settingsRepository.updateApiKeys(apiKeys);
        log.info("AI profile 已删除：profileId={}, remaining={}", profile.id(), stored.size());
    }

    public TestResponse test(String profileId) {
        AiSettingsRepository.StoredProfile profile = requireProfile(profileId);
        AiRuntimeFutureEvent.Runtime runtime = runtime(profile);
        if (!runtime.configured()) {
            return new TestResponse(false, "请先配置 Provider、模型和 API key");
        }
        try {
            providerClient.test(runtime);
            log.info("AI 连接测试成功：profileId={}", profileId);
            return new TestResponse(true, "AI 连接测试成功");
        } catch (Exception e) {
            log.error("AI 连接测试失败", e);
            return new TestResponse(false, "AI 连接测试失败：" + e.getMessage());
        }
    }

    private AiRuntimeFutureEvent.Runtime runtime(AiSettingsRepository.StoredProfile profile) {
        Provider provider = supportedProvider(profile.provider());
        return new AiRuntimeFutureEvent.Runtime(profile.id(), profile.provider(), profile.model(), profile.endpoint().isBlank() ? provider.defaultEndpoint() : profile.endpoint(),
                                                settingsRepository.getApiKey(profile.id()), normalizeMaxTextChars(profile.maxTextChars()));
    }

    private static Profile toProfile(AiSettingsRepository.StoredProfile profile,
                                     Map<String, String> apiKeys) {
        return new Profile(profile.id(), profile.label(), profile.provider(), profile.model(), profile.endpoint(), profile.maxTextChars(), !apiKeys.getOrDefault(profile.id(), "").isBlank());
    }

    private AiSettingsRepository.StoredProfile requireProfile(String profileId) {
        String id = profileId == null ? "" : profileId.trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("profileId 不能为空");
        }
        return settingsRepository.getProfiles().stream().filter(profile -> id.equals(profile.id())).findFirst().orElseThrow(() -> new IllegalArgumentException("AI profile 不存在：" + id));
    }

    private static int normalizeMaxTextChars(Integer value) {
        if (value == null || value <= 0) {
            return DEFAULT_MAX_TEXT_CHARS;
        }
        return Math.max(MIN_MAX_TEXT_CHARS, Math.min(MAX_MAX_TEXT_CHARS, value));
    }

    private static int requireMaxTextChars(int value) {
        if (value < MIN_MAX_TEXT_CHARS || value > MAX_MAX_TEXT_CHARS) {
            throw new IllegalArgumentException("单次文本上限必须在 1,000 到 1,000,000 字符之间");
        }
        return value;
    }

    private static Provider supportedProvider(String value) {
        Provider provider = value == null ? null : PROVIDERS.get(value.trim().toLowerCase());
        if (provider == null) {
            throw new IllegalArgumentException("不支持的 AI Provider：" + value);
        }
        return provider;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static List<ProviderDefaults> providerDefaults() {
        return PROVIDERS.entrySet()
                .stream()
                .map(entry -> new ProviderDefaults(entry.getKey(), PROVIDER_LABELS.getOrDefault(entry.getKey(), entry.getKey()), entry.getValue().defaultModel(), entry.getValue().defaultEndpoint()))
                .toList();
    }

    private static String requireEndpoint(String value) {
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) || uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("endpoint 必须是有效的 http/https URL，且不能包含用户信息");
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("endpoint 不是有效 URL", e);
        }
    }

    private record Provider(String defaultModel, String defaultEndpoint) {
    }

    public record Profile(String id, String label, String provider, String model, String endpoint, Integer maxTextChars, boolean keyConfigured) {
    }

    public record SettingsResponse(List<Profile> profiles, List<ProviderDefaults> providers) {
    }

    public record ProviderDefaults(String id, String label, String defaultModel, String defaultEndpoint) {
    }

    public record ProfileUpdate(String id, String label, String provider, String model, String endpoint, Integer maxTextChars, String apiKey, Boolean clearApiKey) {
    }

    public record TestResponse(boolean success, String message) {
    }
}
