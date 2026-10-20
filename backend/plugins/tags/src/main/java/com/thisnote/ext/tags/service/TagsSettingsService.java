package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.repository.TagsSettingsRepository;
import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
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
        return stored == null || stored.isBlank() ? AiProfilesFutureEvent.DEFAULT_PROFILE_ID : stored;
    }

    public SettingsResponse response() {
        if (eventPublisher == null) {
            return new SettingsResponse(aiProfileId(), List.of(), "AI 核心插件尚未就绪");
        }
        try {
            return new SettingsResponse(aiProfileId(), profiles(), "");
        } catch (Exception e) {
            return new SettingsResponse(aiProfileId(), List.of(), e.getMessage());
        }
    }

    public synchronized SettingsResponse save(SettingsUpdate update) {
        if (update == null) {
            throw new IllegalArgumentException("设置不能为空");
        }
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
        settingsRepository.updateAiProfileId(profileId);
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
                                   List<AiProfileSummary> profiles,
                                   String profilesError) {
    }

    public record SettingsUpdate(String aiProfileId) {
    }
}
