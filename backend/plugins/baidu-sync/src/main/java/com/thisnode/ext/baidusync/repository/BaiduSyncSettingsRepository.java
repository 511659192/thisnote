package com.thisnode.ext.baidusync.repository;

import com.thisnode.ext.baidusync.model.BaiduSyncSettings;
import com.thisnode.plugin.api.util.JsonFileUtils;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

@Repository
public class BaiduSyncSettingsRepository {

    private final Path file;

    public BaiduSyncSettingsRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot) {
        Path pluginDirectory = PluginDataPathUtils.pluginDataDirectory(Path.of(dataRoot), "baidu-sync");
        this.file = pluginDirectory.resolve("credentials.json");
    }

    public synchronized BaiduSyncSettings get() {
        try {
            BaiduSyncSettings settings = JsonFileUtils.read(file, BaiduSyncSettings.class);
            return settings == null ? BaiduSyncSettings.empty() : settings;
        } catch (IOException e) {
            throw new UncheckedIOException("读取百度网盘配置失败 " + file, e);
        }
    }

    public synchronized BaiduSyncSettings updateCredentials(String appName,
                                                            String appKey,
                                                            String secretKey,
                                                            String signKey) {
        BaiduSyncSettings current = get();
        BaiduSyncSettings updated = current.withCredentials(
                appName.isBlank() ? current.appName() : appName.trim(),
                appKey.isBlank() ? current.appKey() : appKey.trim(),
                secretKey.isBlank() ? current.secretKey() : secretKey,
                signKey.isBlank() ? current.signKey() : signKey);
        write(updated);
        return updated;
    }

    public synchronized void updateAutoSync(boolean autoSync) {
        write(get().withAutoSync(autoSync));
    }

    public synchronized void updateTokens(String accessToken,
                                          String refreshToken,
                                          long expiresAt) {
        write(get().withTokens(accessToken, refreshToken, expiresAt));
    }

    private void write(BaiduSyncSettings settings) {
        JsonFileUtils.write(file, settings);
    }
}
