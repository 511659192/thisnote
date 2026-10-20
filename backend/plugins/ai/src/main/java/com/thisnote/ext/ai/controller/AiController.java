package com.thisnote.ext.ai.controller;

import com.thisnote.ext.ai.service.AiSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plugins/ai")
@Slf4j
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
        log.info("收到 AI profile 保存请求：id={}", update == null ? null : update.id());
        return settingsService.save(update);
    }

    @PostMapping("/profiles/delete")
    public void delete(@RequestBody ProfileRequest request) {
        log.info("收到 AI profile 删除请求：profileId={}", request.profileId());
        settingsService.delete(request.profileId());
    }

    @PostMapping("/profiles/test")
    public AiSettingsService.TestResponse test(@RequestBody ProfileRequest request) {
        log.info("收到 AI profile 连接测试请求：profileId={}", request.profileId());
        return settingsService.test(request.profileId());
    }

    public record ProfileRequest(String profileId) {
    }
}
