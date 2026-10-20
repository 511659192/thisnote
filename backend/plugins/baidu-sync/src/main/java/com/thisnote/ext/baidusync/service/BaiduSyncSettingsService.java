package com.thisnote.ext.baidusync.service;

import com.thisnote.ext.baidusync.client.BaiduOAuthClient;
import com.thisnote.ext.baidusync.model.BaiduSyncSettings;
import com.thisnote.ext.baidusync.model.request.ExchangeCodeRequest;
import com.thisnote.ext.baidusync.model.request.UpdateSettingsRequest;
import com.thisnote.ext.baidusync.model.view.SettingsView;
import com.thisnote.ext.baidusync.repository.BaiduSyncSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@Service
public class BaiduSyncSettingsService {

    private final BaiduSyncSettingsRepository settingsRepository;
    private final BaiduOAuthClient oauthClient;
    private final String dataRoot;

    public BaiduSyncSettingsService(BaiduSyncSettingsRepository settingsRepository,
                                    BaiduOAuthClient oauthClient,
                                    @Value("${thisnote.data-root:${user.home}/.thisnote}") String dataRoot) {
        this.settingsRepository = settingsRepository;
        this.oauthClient = oauthClient;
        this.dataRoot = dataRoot;
    }

    public SettingsView getSettings() {
        return settingsView(settingsRepository.getSettings());
    }

    public SettingsView updateSettings(UpdateSettingsRequest request) {
        BaiduSyncSettings settings = settingsRepository.updateCredentials(
                Objects.toString(request.appName(), ""),
                Objects.toString(request.appKey(), ""),
                Objects.toString(request.secretKey(), ""),
                Objects.toString(request.signKey(), ""));
        return settingsView(settings);
    }

    public Map<String, String> authorizationUrl() {
        return Map.of("url", oauthClient.authorizationUrl());
    }

    public SettingsView exchangeCode(ExchangeCodeRequest request) {
        oauthClient.exchangeCode(Objects.toString(request.code(), "").trim());
        return settingsView(settingsRepository.getSettings());
    }

    public SettingsView updateAutoSync(boolean value) {
        settingsRepository.updateAutoSync(value);
        return settingsView(settingsRepository.getSettings());
    }

    private SettingsView settingsView(BaiduSyncSettings settings) {
        Path data = Path.of(dataRoot).toAbsolutePath().normalize();
        Path notesRoot = data.resolve("notes");
        String remoteDirectory = settings.appName().isBlank() ? "" : "/apps/" + settings.appName() + "/notes";
        return new SettingsView(
                settings.appName(),
                !settings.appKey().isBlank(),
                !settings.secretKey().isBlank(),
                !settings.signKey().isBlank(),
                !settings.refreshToken().isBlank() || !settings.accessToken().isBlank(),
                settings.autoSync(),
                notesRoot.toString(),
                remoteDirectory);
    }
}
