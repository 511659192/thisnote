package com.thisnote.ext.aititle.controller;

import com.thisnote.ext.aititle.service.TitleSettingsService;
import com.thisnote.ext.aititle.service.TitleSuggestionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/plugins/ai-title")
@RequiredArgsConstructor
public class TitleController {

    private final TitleSettingsService settingsService;
    private final TitleSuggestionService suggestionService;

    @GetMapping("/settings")
    public TitleSettingsService.SettingsResponse settings() {
        return settingsService.response();
    }

    @PostMapping("/settings")
    public TitleSettingsService.SettingsResponse saveSettings(
            @RequestBody TitleSettingsService.SettingsUpdate update) {
        TitleSettingsService.SettingsResponse response = settingsService.save(update);
        log.info("AI 标题设置已保存：profileId={}", response.aiProfileId());
        return response;
    }

    @PostMapping("/suggest")
    public TitleSuggestionService.SuggestionsResponse suggest(@RequestBody NoteRequest request) {
        log.info("收到 AI 标题候选请求：noteId={}", request.noteId());
        return suggestionService.suggest(request.noteId());
    }

    @PostMapping("/apply")
    public TitleSuggestionService.TitleUpdateResponse apply(@RequestBody TitleUpdateRequest request) {
        log.info("收到 AI 标题应用请求：noteId={}, title={}", request.noteId(), request.title());
        return suggestionService.apply(request.noteId(), request.title());
    }

    public record NoteRequest(String noteId) {
    }

    public record TitleUpdateRequest(String noteId,
                                     String title) {
    }
}
