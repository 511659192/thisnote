package com.thisnote.ext.ai.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class AiProviderClient {

    private static final String OPENCODE_GO_PROVIDER = "opencode-go";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();

    public void test(AiRuntimeFutureEvent.Runtime runtime) {
        String response = complete(runtime, "Reply with exactly OK.", "Connection test", List.of(), 32);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("模型没有返回内容");
        }
    }

    public String complete(AiRuntimeFutureEvent.Runtime runtime,
                           String system,
                           String userText,
                           List<AiCompletionFutureEvent.ImageInput> images,
                           int maxTokens) {
        HttpRequest request = chatRequest(runtime, system, userText, images, maxTokens);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 AI provider", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI provider 请求被中断", e);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            throw new IllegalStateException("AI provider 返回 HTTP " + response.statusCode() + (body.isBlank() ? "" : "：" + body.substring(0, Math.min(400, body.length()))));
        }
        JSONObject body = JSON.parseObject(response.body().getBytes(StandardCharsets.UTF_8));
        return chatText(body);
    }

    private HttpRequest chatRequest(AiRuntimeFutureEvent.Runtime runtime,
                                    String system,
                                    String userText,
                                    List<AiCompletionFutureEvent.ImageInput> images,
                                    int maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", runtime.model());
        body.put("max_tokens", maxTokens);
        JSONArray messages = new JSONArray();
        messages.add(object("role", "system", "content", system));
        messages.add(object("role", "user", "content", chatContent(userText, images)));
        body.put("messages", messages);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(runtime.endpoint()))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + runtime.apiKey())
                .header("Content-Type", "application/json")
                .header("User-Agent", "thisnote-ai/1.0")
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8));
        if (OPENCODE_GO_PROVIDER.equals(runtime.provider())) {
            request.header("x-opencode-session", "ses_" + UUID.randomUUID());
        }
        return request.build();
    }

    private Object chatContent(String text,
                               List<AiCompletionFutureEvent.ImageInput> images) {
        if (images.isEmpty()) {
            return text;
        }
        JSONArray content = new JSONArray();
        content.add(object("type", "text", "text", text));
        images.forEach(image -> content.add(object("type", "image_url", "image_url", object("url", "data:" + image.mediaType() + ";base64," + image.base64()))));
        return content;
    }

    private String chatText(JSONObject body) {
        JSONArray choices = body == null ? null : body.getJSONArray("choices");
        if (choices == null || choices.size() == 0) {
            return "";
        }
        JSONObject message = choices.getJSONObject(0).getJSONObject("message");
        Object content = message == null ? null : message.get("content");
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof JSONArray parts) {
            return parts.stream().filter(JSONObject.class::isInstance).map(JSONObject.class::cast).map(item -> item.getString("text")).filter(Objects::nonNull).collect(Collectors.joining());
        }
        return "";
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return result;
    }

}
