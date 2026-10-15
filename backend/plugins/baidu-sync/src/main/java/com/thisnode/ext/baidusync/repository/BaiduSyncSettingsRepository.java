package com.thisnode.ext.baidusync.repository;

import com.alibaba.fastjson2.JSON;
import com.thisnode.ext.baidusync.model.BaiduSyncSettings;
import com.thisnode.plugin.api.util.FileHelper;
import com.thisnode.plugin.api.util.PluginDataPathUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Repository
public class BaiduSyncSettingsRepository {

    private final Path file;
    private final FileHelper fileHelper;

    public BaiduSyncSettingsRepository(@Value("${thisnode.data-root:${user.home}/.thisnode}") String dataRoot,
                                       FileHelper fileHelper) {
        this.fileHelper = fileHelper;
        Path pluginDirectory = PluginDataPathUtils.pluginDataDirectory(Path.of(dataRoot), "baidu-sync", fileHelper);
        this.file = pluginDirectory.resolve("credentials.json");
    }

    public synchronized BaiduSyncSettings get() {
        try {
            BaiduSyncSettings settings = read();
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
        BaiduSyncSettings updated = current.withCredentials(appName.isBlank() ? current.appName() : appName.trim(),
                                                            appKey.isBlank() ? current.appKey() : appKey.trim(),
                                                            secretKey.isBlank() ? current.secretKey() : secretKey,
                                                            signKey.isBlank() ? current.signKey() : signKey);
        fileHelper.fileWritten(file, updated);
        return updated;
    }

    public synchronized void updateAutoSync(boolean autoSync) {
        fileHelper.fileWritten(file, get().withAutoSync(autoSync));
    }

    public synchronized void updateTokens(String accessToken,
                                          String refreshToken,
                                          long expiresAt) {
        fileHelper.fileWritten(file, get().withTokens(accessToken, refreshToken, expiresAt));
    }

    private BaiduSyncSettings read() throws IOException {
        if (!Files.isRegularFile(file)) return null;
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return json.isBlank() ? null : JSON.parseObject(json, BaiduSyncSettings.class);
    }
}
