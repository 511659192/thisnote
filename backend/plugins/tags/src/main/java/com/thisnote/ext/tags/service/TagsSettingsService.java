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

    public static final int DEFAULT_FONT_SIZE = 15;
    public static final int MIN_FONT_SIZE = 10;
    public static final int MAX_FONT_SIZE = 32;

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

    public int fontSize() {
        int fontSize = settingsRepository.getFontSize();
        return fontSize < MIN_FONT_SIZE || fontSize > MAX_FONT_SIZE ? DEFAULT_FONT_SIZE : fontSize;
    }

    public SettingsResponse response() {
        if (eventPublisher == null) {
            log.warn("AI 核心插件尚未就绪，无法读取标签 profile");
            return new SettingsResponse(aiProfileId(), autoEnabled(), fontSize(), List.of(), "AI 核心插件尚未就绪");
        }
        try {
            return new SettingsResponse(aiProfileId(), autoEnabled(), fontSize(), profiles(), "");
        } catch (Exception e) {
            log.warn("读取标签 AI profile 失败：{}", e.getMessage());
            return new SettingsResponse(aiProfileId(), autoEnabled(), fontSize(), List.of(), e.getMessage());
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
        int fontSize = update.fontSize() == null ? fontSize() : requireFontSize(update.fontSize());
        settingsRepository.update(profileId, update.autoEnabled(), fontSize);
        log.info("标签设置已保存：profileId={}, autoEnabled={}, fontSize={}",
                profileId, update.autoEnabled(), fontSize);
        return response();
    }

    private int requireFontSize(int value) {
        if (value < MIN_FONT_SIZE || value > MAX_FONT_SIZE) {
            log.warn("标签字号设置被拒绝：fontSize={}, min={}, max={}",
                    value, MIN_FONT_SIZE, MAX_FONT_SIZE);
            throw new IllegalArgumentException("标签字号必须在 10 到 32 之间");
        }
        return value;
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
                                   int fontSize,
                                   List<AiProfileSummary> profiles,
                                   String profilesError) {
    }

    public record SettingsUpdate(String aiProfileId, boolean autoEnabled, Integer fontSize) {
    }
}