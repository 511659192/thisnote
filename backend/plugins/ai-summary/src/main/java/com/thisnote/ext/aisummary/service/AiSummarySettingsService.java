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

    public static final int DEFAULT_FONT_SIZE = 15;
    public static final int MIN_FONT_SIZE = 10;
    public static final int MAX_FONT_SIZE = 32;

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

    public int fontSize() {
        int fontSize = settingsRepository.getFontSize();
        return fontSize < MIN_FONT_SIZE || fontSize > MAX_FONT_SIZE ? DEFAULT_FONT_SIZE : fontSize;
    }

    public SettingsResponse response() {
        if (eventPublisher == null) {
            return new SettingsResponse(aiProfileId(), autoEnabled(), fontSize(), List.of(), "AI 核心插件尚未就绪");
        }
        try {
            return new SettingsResponse(aiProfileId(), autoEnabled(), fontSize(), profiles(), "");
        } catch (Exception e) {
            log.warn("读取 AI 总结 profile 失败：{}", e.getMessage());
            return new SettingsResponse(aiProfileId(), autoEnabled(), fontSize(), List.of(), e.getMessage());
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
        int fontSize = update.fontSize() == null ? fontSize() : requireFontSize(update.fontSize());
        settingsRepository.update(profileId, update.autoEnabled(), fontSize);
        log.info("AI 总结设置已保存：profileId={}, autoEnabled={}, fontSize={}",
                profileId, update.autoEnabled(), fontSize);
        return response();
    }

    private int requireFontSize(int value) {
        if (value < MIN_FONT_SIZE || value > MAX_FONT_SIZE) {
            log.warn("AI 总结字号设置被拒绝：fontSize={}, min={}, max={}",
                    value, MIN_FONT_SIZE, MAX_FONT_SIZE);
            throw new IllegalArgumentException("AI 总结字号必须在 10 到 32 之间");
        }
        return value;
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
                                   int fontSize,
                                   List<AiProfileSummary> profiles,
                                   String profilesError) {
    }

    public record SettingsUpdate(String aiProfileId, boolean autoEnabled, Integer fontSize) {
    }
}