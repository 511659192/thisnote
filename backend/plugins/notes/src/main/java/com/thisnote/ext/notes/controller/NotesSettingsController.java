package com.thisnote.ext.notes.controller;

import com.thisnote.ext.notes.service.NotesSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/notes/settings")
@RequiredArgsConstructor
public class NotesSettingsController {

    private final NotesSettingsService settingsService;

    @GetMapping("/get")
    public NotesSettingsService.SettingsResponse get() {
        return settingsService.response();
    }

    @PostMapping("/update")
    public NotesSettingsService.SettingsResponse update(@RequestBody NotesSettingsService.FontSizeUpdate update) {
        log.info("收到笔记设置更新请求：fontSize={}", update == null ? null : update.fontSize());
        return settingsService.update(update);
    }

    @PostMapping("/update-root")
    public NotesSettingsService.SettingsResponse updateDataRoot(@RequestBody NotesSettingsService.DataRootUpdate update) {
        log.info("收到数据根目录更新请求：dataRoot={}", update == null ? null : update.dataRoot());
        return settingsService.updateDataRoot(update);
    }
}
