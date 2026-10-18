package com.thisnode.ext.baidusync.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.thisnode.ext.baidusync.model.BaiduSyncSettings;
import com.thisnode.ext.baidusync.repository.BaiduSyncSettingsRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BaiduOAuthClient {

    private static final String AUTHORIZE_URL = "https://openapi.baidu.com/oauth/2.0/authorize";
    private static final String TOKEN_URL = "https://openapi.baidu.com/oauth/2.0/token";
    private final BaiduSyncSettingsRepository settingsRepository;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public BaiduOAuthClient(BaiduSyncSettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public String authorizationUrl() {
        BaiduSyncSettings settings = requireClientSettings();
        return AUTHORIZE_URL + "?" + BaiduPanClient.query(
                Map.of("response_type", "code", "client_id", settings.appKey(), "redirect_uri", "oob", "scope", "basic,netdisk", "display", "tv", "qrcode", "1", "force_login", "1"));
    }

    public void exchangeCode(String code) {
        BaiduSyncSettings settings = requireClientSettings();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("client_id", settings.appKey());
        params.put("client_secret", settings.secretKey());
        params.put("redirect_uri", "oob");
        saveTokens(postToken(params), settings.refreshToken());
    }

    public synchronized String accessToken() {
        BaiduSyncSettings settings = requireClientSettings();
        if (!settings.accessToken().isBlank() && settings.accessTokenExpiresAt() > System.currentTimeMillis() + 60_000) {
            return settings.accessToken();
        }
        if (settings.refreshToken().isBlank()) {
            throw new IllegalStateException("请先完成百度网盘授权");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("grant_type", "refresh_token");
        params.put("refresh_token", settings.refreshToken());
        params.put("client_id", settings.appKey());
        params.put("client_secret", settings.secretKey());
        JSONObject token = postToken(params);
        saveTokens(token, settings.refreshToken());
        return settingsRepository.get().accessToken();
    }

    private BaiduSyncSettings requireClientSettings() {
        BaiduSyncSettings settings = settingsRepository.get();
        if (settings.appKey().isBlank() || settings.secretKey().isBlank()) {
            throw new IllegalStateException("请先填写百度 AppKey 和 SecretKey 并保存");
        }
        return settings;
    }

    private JSONObject postToken(Map<String, String> params) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL + "?" + BaiduPanClient.query(params))).timeout(Duration.ofSeconds(30)).header("User-Agent", "thisnote-baidu-sync").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JSONObject body = JSON.parseObject(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300 || body == null || body.containsKey("error") || body.getString("access_token") == null) {
                throw new IllegalStateException("百度 OAuth 授权失败：" + oauthError(body, response.statusCode()));
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("百度 OAuth 请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接百度 OAuth 服务", e);
        }
    }

    private void saveTokens(JSONObject token,
                            String previousRefreshToken) {
        String refreshToken = token.getString("refresh_token");
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = previousRefreshToken;
        }
        if (refreshToken == null) {
            refreshToken = "";
        }
        long expiresAt = System.currentTimeMillis() + token.getLongValue("expires_in") * 1000;
        settingsRepository.updateTokens(token.getString("access_token"), refreshToken, expiresAt);
    }

    private String oauthError(JSONObject body,
                              int statusCode) {
        if (body == null) {
            return "HTTP " + statusCode;
        }
        String message = body.getString("error_description");
        if (message == null || message.isBlank()) {
            message = body.getString("error");
        }
        return message == null || message.isBlank() ? "HTTP " + statusCode : message;
    }
}
