package com.thisnote.ext.guide.controller;

import com.thisnote.ext.guide.service.GuideService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/plugins/guide")
@RequiredArgsConstructor
@Slf4j
public class GuideController {

    private final GuideService guideService;

    @GetMapping("/environment")
    public GuideService.GuideEnvironment environment() {
        GuideService.GuideEnvironment status = guideService.environment();
        log.debug("读取指南环境状态：done={}, detail={}", status.done(), status.detail());
        return status;
    }

}
