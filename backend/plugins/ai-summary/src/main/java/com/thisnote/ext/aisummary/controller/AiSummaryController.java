package com.thisnote.ext.aisummary.controller;

import com.thisnote.ext.aisummary.service.AiSummaryService;
import com.thisnote.ext.aisummary.service.AiSummarySettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/plugins/ai-summary")
@RequiredArgsConstructor
public class AiSummaryController {

    private final AiSummaryService summaryService;
    private final AiSummarySettingsService settingsService;

    @GetMapping("/get")
    public AiSummaryService.SummaryView get(@RequestParam String noteId) {
        return summaryService.getOne(noteId);
    }

    @PostMapping("/generate")
    public AiSummaryService.SummaryView generate(@RequestBody GenerateRequest request) {
        log.info("收到 AI 总结生成请求：noteId={}, force={}, automatic={}",
                request.noteId(), request.force(), request.automatic());
        return summaryService.generate(request.noteId(), request.force(), request.automatic());
    }

    @GetMapping("/settings")
    public AiSummarySettingsService.SettingsResponse settings() {
        return settingsService.response();
    }

    @PostMapping("/settings")
    public AiSummarySettingsService.SettingsResponse saveSettings(
            @RequestBody AiSummarySettingsService.SettingsUpdate update) {
        AiSummarySettingsService.SettingsResponse response = settingsService.save(update);
        log.info("AI 总结设置已保存：profileId={}", response.aiProfileId());
        return response;
    }

    public record GenerateRequest(String noteId, boolean force, boolean automatic) {
    }
}
