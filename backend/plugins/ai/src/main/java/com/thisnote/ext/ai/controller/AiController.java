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

    @PostMapping("/profiles/save")
    public AiSettingsService.Profile save(@RequestBody AiSettingsService.ProfileUpdate update) {
        return settingsService.save(update);
    }

    @PostMapping("/profiles/delete")
    public void delete(@RequestBody ProfileRequest request) {
        settingsService.delete(request.profileId());
    }

    @PostMapping("/profiles/test")
    public AiSettingsService.TestResponse test(@RequestBody ProfileRequest request) {
        return settingsService.test(request.profileId());
    }

    public record ProfileRequest(String profileId) {
    }
}
