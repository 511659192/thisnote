package com.thisnode.ext.baidusync.controller;

import com.thisnode.ext.baidusync.client.BaiduOAuthClient;
import com.thisnode.ext.baidusync.model.BaiduSyncConnectionProbe;
import com.thisnode.ext.baidusync.model.BaiduSyncPreview;
import com.thisnode.ext.baidusync.model.BaiduSyncSettings;
import com.thisnode.ext.baidusync.model.BaiduSyncStatus;
import com.thisnode.ext.baidusync.model.request.ExchangeCodeRequest;
import com.thisnode.ext.baidusync.model.request.UpdateSettingsRequest;
import com.thisnode.ext.baidusync.model.view.SettingsView;
import com.thisnode.ext.baidusync.repository.BaiduSyncSettingsRepository;
import com.thisnode.ext.baidusync.service.BaiduSyncService;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/plugins/baidu-sync")
@RequiredArgsConstructor
public class BaiduSyncController {

    private final BaiduSyncSettingsRepository settingsRepository;
    private final BaiduOAuthClient oauthClient;
    private final BaiduSyncService syncService;

    @Value("${thisnode.data-root:${user.home}/.thisnode}")
    private String dataRoot;

    @GetMapping("/get-settings")
    public SettingsView getSettings() {
        return settingsView(settingsRepository.get());
    }

    @PostMapping("/update-settings")
    public SettingsView updateSettings(@RequestBody UpdateSettingsRequest request) {
        return settingsView(settingsRepository.updateCredentials(
                value(request.appName()), value(request.appKey()),
                value(request.secretKey()), value(request.signKey())));
    }

    @GetMapping("/get-authorization-url")
    public Map<String, String> authorizationUrl() {
        return Map.of("url", oauthClient.authorizationUrl());
    }

    @PostMapping("/exchange-code")
    public SettingsView exchangeCode(@RequestBody ExchangeCodeRequest request) {
        oauthClient.exchangeCode(value(request.code()).trim());
        return settingsView(settingsRepository.get());
    }

    @PostMapping("/update-auto-sync")
    public SettingsView updateAutoSync(@RequestParam boolean value) {
        settingsRepository.updateAutoSync(value);
        return settingsView(settingsRepository.get());
    }

    @PostMapping("/sync")
    public BaiduSyncStatus sync() {
        return syncService.startSync();
    }

    @GetMapping("/get-sync-status")
    public BaiduSyncStatus syncStatus() {
        return syncService.status();
    }

    @GetMapping("/get-connection-probe")
    public BaiduSyncConnectionProbe connectionProbe() {
        return syncService.probeConnection();
    }

    @GetMapping("/get-diff-preview")
    public BaiduSyncPreview diffPreview() throws IOException {
        return syncService.preview();
    }

    private SettingsView settingsView(BaiduSyncSettings settings) {
        Path data = Path.of(dataRoot).toAbsolutePath().normalize();
        Path notesRoot = PluginDataPathUtils.configuredNotesRoot(data.resolve("notes"));
        String remoteDirectory = settings.appName().isBlank()
                ? ""
                : "/apps/" + settings.appName() + "/notes";
        return new SettingsView(settings.appName(),
                !settings.appKey().isBlank(),
                !settings.secretKey().isBlank(),
                !settings.signKey().isBlank(),
                !settings.refreshToken().isBlank() || !settings.accessToken().isBlank(),
                settings.autoSync(),
                notesRoot.toString(),
                remoteDirectory);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

}
