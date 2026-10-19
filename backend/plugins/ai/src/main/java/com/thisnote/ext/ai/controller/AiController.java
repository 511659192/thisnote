package com.thisnote.ext.ai.controller;

import com.thisnote.ext.ai.service.AiSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plugins/ai")
public class AiController {

    private final AiSettingsService settingsService;

    public AiController(AiSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/settings")
    public AiSettingsService.SettingsResponse settings() {
        return settingsService.response();
    }

    @PostMapping("/settings")
    public AiSettingsService.SettingsResponse save(@RequestBody AiSettingsService.SettingsUpdate update) {
        return settingsService.save(update);
    }

    @PostMapping("/test")
    public AiSettingsService.TestResponse test() {
        return settingsService.test();
    }
}
