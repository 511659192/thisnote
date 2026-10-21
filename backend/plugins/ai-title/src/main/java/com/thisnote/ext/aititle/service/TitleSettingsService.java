package com.thisnote.ext.aititle.service;

import com.thisnote.ext.aititle.repository.TitleSettingsRepository;
import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TitleSettingsService {

    private static final long QUERY_TIMEOUT_SECONDS = 30;

    private final TitleSettingsRepository settingsRepository;
    private ApplicationEventPublisher eventPublisher;

    public TitleSettingsService(TitleSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public String requireAiProfileId() {
        String stored = settingsRepository.getAiProfileId();
        String profileId = stored == null ? "" : stored.trim();
        if (profileId.isEmpty()) {
            throw new IllegalStateException("请先在设置中为 AI 标题选择 completion 配置");
        }
        return profileId;
    }

    public SettingsResponse response() {
        String profileId = settingsRepository.getAiProfileId();
        if (eventPublisher == null) {
            return new SettingsResponse(profileId, List.of(), "AI 核心插件尚未就绪");
        }
        try {
            return new SettingsResponse(profileId, profiles(), "");
        } catch (Exception e) {
            log.warn("读取 AI 标题 profile 失败：{}", e.getMessage());
            return new SettingsResponse(profileId, List.of(), e.getMessage());
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
        settingsRepository.updateAiProfileId(profileId);
        log.info("AI 标题设置已保存：profileId={}", profileId);
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
                                   List<AiProfileSummary> profiles,
                                   String profilesError) {
    }

    public record SettingsUpdate(String aiProfileId) {
    }
}
