package com.thisnote.ext.ai.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.ext.ai.service.AiCompletionExecutor;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AiProviderClient {

    private static final String OPENCODE_GO_PROVIDER = "opencode-go";
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MILLIS = 2_000;
    private static final long MAX_RETRY_WAIT_MILLIS = 10_000;
    private static final long FAILURE_COOLDOWN_MILLIS = 10_000;

    private final AiCompletionExecutor completionExecutor;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();

    public AiProviderClient(AiCompletionExecutor completionExecutor) {
        this.completionExecutor = completionExecutor;
    }

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
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpResponse<String> response = send(request);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JSONObject body = JSON.parseObject(response.body().getBytes(StandardCharsets.UTF_8));
                return chatText(body);
            }

            String errorBody = response.body() == null ? "" : response.body();
            long retryAfterMillis = retryAfterMillis(response, runtime.profileId());
            if (isRetryable(response.statusCode()) && attempt < MAX_ATTEMPTS) {
                long delayMillis = retryDelayMillis(retryAfterMillis, attempt);
                completionExecutor.deferQueuedRequests(delayMillis, runtime.profileId());
                log.warn("AI provider 请求暂时失败，准备重试：profileId={}, status={}, attempt={}/{}, delayMs={}",
                         runtime.profileId(), response.statusCode(), attempt, MAX_ATTEMPTS, delayMillis);
                sleepBeforeRetry(delayMillis);
                continue;
            }

            if (isRetryable(response.statusCode())) {
                completionExecutor.deferQueuedRequests(
                        retryAfterMillis > 0 ? retryAfterMillis : FAILURE_COOLDOWN_MILLIS,
                        runtime.profileId()
                );
            }
            throw new IllegalStateException("AI provider 返回 HTTP " + response.statusCode()
                    + (errorBody.isBlank() ? "" : "：" + errorBody.substring(0, Math.min(400, errorBody.length()))));
        }
        throw new IllegalStateException("AI provider 请求失败");
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 AI provider", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI provider 请求被中断", e);
        }
    }

    private static boolean isRetryable(int statusCode) {
        return statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    private static long retryDelayMillis(long retryAfterMillis, int attempt) {
        if (retryAfterMillis > 0) {
            return Math.min(retryAfterMillis, MAX_RETRY_WAIT_MILLIS);
        }
        return Math.min(RETRY_BASE_DELAY_MILLIS << (attempt - 1), MAX_RETRY_WAIT_MILLIS);
    }

    private static void sleepBeforeRetry(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI provider 重试等待被中断", e);
        }
    }

    private long retryAfterMillis(HttpResponse<String> response, String profileId) {
        String value = response.headers().firstValue("Retry-After").orElse("").trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            long seconds = Long.parseLong(value);
            if (seconds < 0) {
                log.warn("AI provider 返回负 Retry-After：profileId={}, value={}", profileId, value);
                return 0;
            }
            return TimeUnit.SECONDS.toMillis(seconds);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), retryAt);
                return delay.isNegative() ? 0 : delay.toMillis();
            } catch (DateTimeParseException | ArithmeticException e) {
                log.warn("AI provider 返回无法解析的 Retry-After：profileId={}, value={}", profileId, value, e);
                return 0;
            }
        }
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
