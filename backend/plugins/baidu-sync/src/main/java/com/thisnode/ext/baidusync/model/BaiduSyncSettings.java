package com.thisnode.ext.baidusync.model;

import java.util.Objects;

public record BaiduSyncSettings(String appName,
                                String appKey,
                                String secretKey,
                                String signKey,
                                String accessToken,
                                String refreshToken,
                                long accessTokenExpiresAt,
                                boolean autoSync) {

    public BaiduSyncSettings {
        appName = Objects.toString(appName, "");
        appKey = Objects.toString(appKey, "");
        secretKey = Objects.toString(secretKey, "");
        signKey = Objects.toString(signKey, "");
        accessToken = Objects.toString(accessToken, "");
        refreshToken = Objects.toString(refreshToken, "");
    }

    public static BaiduSyncSettings empty() {
        return new BaiduSyncSettings("", "", "", "", "", "", 0, false);
    }

    public BaiduSyncSettings withCredentials(String appName,
                                             String appKey,
                                             String secretKey,
                                             String signKey) {
        boolean clientChanged = !this.appKey.equals(appKey) || !this.secretKey.equals(secretKey);
        return new BaiduSyncSettings(appName, appKey, secretKey, signKey,
                                     clientChanged ? "" : accessToken,
                                     clientChanged ? "" : refreshToken,
                                     clientChanged ? 0 : accessTokenExpiresAt,
                                     autoSync);
    }

    public BaiduSyncSettings withTokens(String accessToken,
                                        String refreshToken,
                                        long expiresAt) {
        return new BaiduSyncSettings(appName, appKey, secretKey, signKey,
                                     accessToken, refreshToken, expiresAt, autoSync);
    }

    public BaiduSyncSettings withAutoSync(boolean value) {
        return new BaiduSyncSettings(appName, appKey, secretKey, signKey,
                                     accessToken, refreshToken, accessTokenExpiresAt, value);
    }
}
