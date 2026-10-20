package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.repository.TagsSettingsRepository;
import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class TagsSettingsService {

    private final TagsSettingsRepository settingsRepository;
    private ApplicationEventPublisher eventPublisher;

    public TagsSettingsService(TagsSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public String aiProfileId() {
        String stored = settingsRepository.getAiProfileId();
        return stored == null ? "" : stored;
    }

    public boolean autoEnabled() {
        return settingsRepository.getAutoEnabled();
    }

    public SettingsResponse response() {
        if (eventPublisher == null) {
            log.warn("AI 核心插件尚未就绪，无法读取标签 profile");
            return new SettingsResponse(aiProfileId(), autoEnabled(), List.of(), "AI 核心插件尚未就绪");
        }
        try {
            return new SettingsResponse(aiProfileId(), autoEnabled(), profiles(), "");
        } catch (Exception e) {
            log.warn("读取标签 AI profile 失败：{}", e.getMessage());
            return new SettingsResponse(aiProfileId(), autoEnabled(), List.of(), e.getMessage());
        }
    }

    public synchronized SettingsResponse save(SettingsUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
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
        settingsRepository.update(profileId, update.autoEnabled());
        log.info("标签设置已保存：profileId={}, autoEnabled={}", profileId, update.autoEnabled());
        return response();
    }

    public List<AiProfileSummary> profiles() {
        if (eventPublisher == null) {
            return List.of();
        }
        AiProfilesFutureEvent request = AiProfilesFutureEvent.request();
        eventPublisher.publishEvent(request);
        if (!request.profiles().isDone()) {
            throw new IllegalStateException("AI 核心插件未响应");
        }
        return request.profiles().getNow(List.of());
    }

    public record SettingsResponse(String aiProfileId,
                                   boolean autoEnabled,
                                   List<AiProfileSummary> profiles,
                                   String profilesError) {
    }

    public record SettingsUpdate(String aiProfileId, boolean autoEnabled) {
    }
}
