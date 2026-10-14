package com.thisnode.ext.baidusync.model;

public record BaiduSyncSettings(String appName,
                                String appKey,
                                String secretKey,
                                String signKey,
                                String accessToken,
                                String refreshToken,
                                long accessTokenExpiresAt,
                                boolean autoSync) {

    public BaiduSyncSettings {
        appName = value(appName);
        appKey = value(appKey);
        secretKey = value(secretKey);
        signKey = value(signKey);
        accessToken = value(accessToken);
        refreshToken = value(refreshToken);
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

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
