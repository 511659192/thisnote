package com.thisnote.ext.baidusync.controller;

import com.thisnote.ext.baidusync.model.BaiduSyncPreview;
import com.thisnote.ext.baidusync.model.BaiduSyncStatus;
import com.thisnote.ext.baidusync.model.request.ExchangeCodeRequest;
import com.thisnote.ext.baidusync.model.request.UpdateSettingsRequest;
import com.thisnote.ext.baidusync.model.view.SettingsView;
import com.thisnote.ext.baidusync.service.BaiduSyncSettingsService;
import com.thisnote.ext.baidusync.service.BaiduSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins/baidu-sync")
@RequiredArgsConstructor
public class BaiduSyncController {

    private final BaiduSyncSettingsService baiduSyncSettingsService;
    private final BaiduSyncService baiduSyncService;

    @GetMapping("/get-settings")
    public SettingsView getSettings() {
        return baiduSyncSettingsService.getSettings();
    }

    @PostMapping("/update-settings")
    public SettingsView updateSettings(@RequestBody UpdateSettingsRequest request) {
        return baiduSyncSettingsService.updateSettings(request);
    }

    @GetMapping("/get-authorization-url")
    public Map<String, String> authorizationUrl() {
        return baiduSyncSettingsService.authorizationUrl();
    }

    @PostMapping("/exchange-code")
    public SettingsView exchangeCode(@RequestBody ExchangeCodeRequest request) {
        return baiduSyncSettingsService.exchangeCode(request);
    }

    @PostMapping("/update-auto-sync")
    public SettingsView updateAutoSync(@RequestParam boolean value) {
        return baiduSyncSettingsService.updateAutoSync(value);
    }

    @PostMapping("/sync")
    public BaiduSyncStatus sync() {
        return baiduSyncService.startSync();
    }

    @GetMapping("/get-sync-status")
    public BaiduSyncStatus syncStatus() {
        return baiduSyncService.status();
    }

    @GetMapping("/get-diff-preview")
    public BaiduSyncPreview diffPreview() throws IOException {
        return baiduSyncService.preview();
    }

}
