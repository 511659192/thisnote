package com.thisnote.ext.aisummary.service;

import com.thisnote.ext.aisummary.repository.AiSummarySettingsRepository;
import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class AiSummarySettingsService {

    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final AiSummarySettingsRepository settingsRepository;
    private ApplicationEventPublisher eventPublisher;

    public AiSummarySettingsService(AiSummarySettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public String aiProfileId() {
        String stored = settingsRepository.getAiProfileId();
        return stored == null ? "" : stored;
    }

    public String requireAiProfileId() {
        String profileId = aiProfileId();
        if (profileId.isBlank()) {
            throw new IllegalStateException("请先在设置中为 AI 总结选择 completion 配置");
        }
        return profileId;
    }

    public boolean autoEnabled() {
        return settingsRepository.getAutoEnabled();
    }

    public SettingsResponse response() {
        if (eventPublisher == null) {
            return new SettingsResponse(aiProfileId(), autoEnabled(), List.of(), "AI 核心插件尚未就绪");
        }
        try {
            return new SettingsResponse(aiProfileId(), autoEnabled(), profiles(), "");
        } catch (Exception e) {
            log.warn("读取 AI 总结 profile 失败：{}", e.getMessage());
            return new SettingsResponse(aiProfileId(), autoEnabled(), List.of(), e.getMessage());
        }
    }

    public synchronized SettingsResponse save(SettingsUpdate update) {
        if (update == null || update.aiProfileId() == null || update.aiProfileId().isBlank()) {
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
        settingsRepository.update(profileId, update.autoEnabled());
        log.info("AI 总结设置已保存：profileId={}, autoEnabled={}", profileId, update.autoEnabled());
        return response();
    }

    private List<AiProfileSummary> profiles() {
        if (eventPublisher == null) {
            throw new IllegalStateException("AI 核心插件尚未就绪");
        }
        AiProfilesFutureEvent request = AiProfilesFutureEvent.request();
        eventPublisher.publishEvent(request);
        return request.result().orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    public record SettingsResponse(String aiProfileId,
                                   boolean autoEnabled,
                                   List<AiProfileSummary> profiles,
                                   String profilesError) {
    }

    public record SettingsUpdate(String aiProfileId, boolean autoEnabled) {
    }
}
