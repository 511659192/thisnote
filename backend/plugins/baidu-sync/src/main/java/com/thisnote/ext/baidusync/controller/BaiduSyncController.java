package com.thisnote.ext.baidusync.controller;

import com.thisnote.ext.baidusync.model.BaiduSyncPreview;
import com.thisnote.ext.baidusync.model.BaiduSyncStatus;
import com.thisnote.ext.baidusync.model.request.ExchangeCodeRequest;
import com.thisnote.ext.baidusync.model.request.UpdateSettingsRequest;
import com.thisnote.ext.baidusync.model.view.SettingsView;
import com.thisnote.ext.baidusync.service.BaiduSyncSettingsService;
import com.thisnote.ext.baidusync.service.BaiduSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins/baidu-sync")
@RequiredArgsConstructor
@Slf4j
public class BaiduSyncController {

    private final BaiduSyncSettingsService baiduSyncSettingsService;
    private final BaiduSyncService baiduSyncService;

    @GetMapping("/get-settings")
    public SettingsView getSettings() {
        return baiduSyncSettingsService.getSettings();
    }

    @PostMapping("/update-settings")
    public SettingsView updateSettings(@RequestBody UpdateSettingsRequest request) {
        log.info("收到百度网盘设置更新请求：appName={}", request.appName());
        return baiduSyncSettingsService.updateSettings(request);
    }

    @GetMapping("/get-authorization-url")
    public Map<String, String> authorizationUrl() {
        return baiduSyncSettingsService.authorizationUrl();
    }

    @PostMapping("/exchange-code")
    public SettingsView exchangeCode(@RequestBody ExchangeCodeRequest request) {
        log.info("收到百度网盘授权码交换请求");
        return baiduSyncSettingsService.exchangeCode(request);
    }

    @PostMapping("/update-auto-sync")
    public SettingsView updateAutoSync(@RequestParam boolean value) {
        log.info("百度网盘自动同步开关更新：value={}", value);
        return baiduSyncSettingsService.updateAutoSync(value);
    }

    @PostMapping("/sync")
    public BaiduSyncStatus sync() {
        log.info("收到百度网盘同步请求");
        return baiduSyncService.startSync();
    }

    @GetMapping("/get-sync-status")
    public BaiduSyncStatus syncStatus() {
        return baiduSyncService.status();
    }

    @GetMapping("/get-diff-preview")
    public BaiduSyncPreview diffPreview() throws IOException {
        log.debug("查询百度网盘差异预览");
        return baiduSyncService.preview();
    }

}
