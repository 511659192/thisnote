package com.thisnode.ext.baidusync.controller;

import com.thisnode.ext.baidusync.client.BaiduOAuthClient;
import com.thisnode.ext.baidusync.model.BaiduSyncPreview;
import com.thisnode.ext.baidusync.model.BaiduSyncSettings;
import com.thisnode.ext.baidusync.model.BaiduSyncStatus;
import com.thisnode.ext.baidusync.model.request.ExchangeCodeRequest;
import com.thisnode.ext.baidusync.model.request.UpdateSettingsRequest;
import com.thisnode.ext.baidusync.model.view.SettingsView;
import com.thisnode.ext.baidusync.repository.BaiduSyncSettingsRepository;
import com.thisnode.ext.baidusync.service.BaiduSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/plugins/baidu-sync")
@RequiredArgsConstructor
public class BaiduSyncController {

    private final BaiduSyncSettingsRepository baiduSyncSettingsRepository;
    private final BaiduOAuthClient baiduOAuthClient;
    private final BaiduSyncService baiduSyncService;
    @Value("${thisnode.data-root:${user.home}/.thisnode}")
    private String dataRoot;

    @GetMapping("/get-settings")
    public SettingsView getSettings() {
        return settingsView(baiduSyncSettingsRepository.get());
    }

    @PostMapping("/update-settings")
    public SettingsView updateSettings(@RequestBody UpdateSettingsRequest request) {
        BaiduSyncSettings settings = baiduSyncSettingsRepository.updateCredentials(Objects.toString(request.appName(), ""),
                                                                                   Objects.toString(request.appKey(), ""),
                                                                                   Objects.toString(request.secretKey(), ""),
                                                                                   Objects.toString(request.signKey(), ""));
        return settingsView(settings);
    }

    @GetMapping("/get-authorization-url")
    public Map<String, String> authorizationUrl() {
        return Map.of("url", baiduOAuthClient.authorizationUrl());
    }

    @PostMapping("/exchange-code")
    public SettingsView exchangeCode(@RequestBody ExchangeCodeRequest request) {
        baiduOAuthClient.exchangeCode(Objects.toString(request.code(), "").trim());
        return settingsView(baiduSyncSettingsRepository.get());
    }

    @PostMapping("/update-auto-sync")
    public SettingsView updateAutoSync(@RequestParam boolean value) {
        baiduSyncSettingsRepository.updateAutoSync(value);
        return settingsView(baiduSyncSettingsRepository.get());
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

    private SettingsView settingsView(BaiduSyncSettings settings) {
        Path data = Path.of(dataRoot).toAbsolutePath().normalize();
        Path notesRoot = data.resolve("notes");
        String remoteDirectory = settings.appName().isBlank() ? "" : "/apps/" + settings.appName() + "/notes";
        return new SettingsView(settings.appName(), !settings.appKey().isBlank(), !settings.secretKey().isBlank(), !settings.signKey().isBlank(),
                                !settings.refreshToken().isBlank() || !settings.accessToken().isBlank(), settings.autoSync(), notesRoot.toString(),
                                remoteDirectory);
    }
}
