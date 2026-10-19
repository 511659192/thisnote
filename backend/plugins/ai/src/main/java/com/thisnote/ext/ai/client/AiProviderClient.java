package com.thisnote.ext.ai.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.event.AiRuntimeEvent;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class AiProviderClient {

    private static final String OPENCODE_GO_URL = "https://opencode.ai/inference/go/openai/v1/chat/completions";
    private static final String TAG_PROMPT = """
            Extract 10 to 20 high-level, reusable business tags from the note text and images.
            Return JSON only in the form {"tags":["tag"]}.
            Tags must be short concepts, not section headings, file names, code symbols, URLs, or full sentences.
            Prefer concepts that help find the note later. Deduplicate and keep every tag under 24 characters.
            Use the note's dominant language for the tag labels.
            """;
    private static final Pattern FILE_EXTENSION = Pattern.compile(".*\\.(?:java|md|json|yml|yaml|xml|ts|tsx|js|jsx|css|html|properties)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMEL_CASE = Pattern.compile("[a-z][A-Z]");
    private static final Pattern PATH_OR_CODE = Pattern.compile("[/\\\\`()\\[\\]{}<>=|$]");
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[，,、;；:：。.!?！？]");

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public void test(AiRuntimeEvent.Runtime runtime) {
        String response = complete(runtime, "Reply with exactly OK.", "Connection test", List.of(), 32);
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("模型没有返回内容");
        }
    }

    public List<String> suggestTags(AiRuntimeEvent.Runtime runtime,
                                    String noteText,
                                    List<ImageInput> images) {
        String response = complete(runtime, TAG_PROMPT, noteText, images, 1200);
        List<String> tags = parseTags(response);
        return tags.isEmpty() ? parseTagsFromListFallback(response) : tags;
    }

    private String complete(AiRuntimeEvent.Runtime runtime,
                            String system,
                            String userText,
                            List<ImageInput> images,
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

    private HttpRequest chatRequest(AiRuntimeEvent.Runtime runtime,
                                    String system,
                                    String userText,
                                    List<ImageInput> images,
                                    int maxTokens) {
        JSONObject body = new JSONObject();
        body.put("model", runtime.model());
        body.put("max_tokens", maxTokens);
        JSONArray messages = new JSONArray();
        messages.add(object("role", "system", "content", system));
        messages.add(object("role", "user", "content", chatContent(userText, images)));
        body.put("messages", messages);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(OPENCODE_GO_URL))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + runtime.apiKey())
                .header("Content-Type", "application/json")
                .header("User-Agent", "thisnote-ai/1.0")
                .header("x-opencode-session", "ses_" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8));
        return request.build();
    }

    private Object chatContent(String text,
                               List<ImageInput> images) {
        if (images.isEmpty()) {
            return text;
        }
        JSONArray content = new JSONArray();
        content.add(object("type", "text", "text", text));
        for (ImageInput image : images) {
            content.add(object("type", "image_url", "image_url", object("url", "data:" + image.mediaType() + ";base64," + image.base64())));
        }
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
            StringBuilder result = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof JSONObject item && item.getString("text") != null) {
                    result.append(item.getString("text"));
                }
            }
            return result.toString();
        }
        return "";
    }

    private List<String> parseTags(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JSONObject object = JSON.parseObject(response.substring(start, end + 1).getBytes(StandardCharsets.UTF_8));
                return cleanTags(object.getJSONArray("tags"));
            } catch (RuntimeException ignored) {
                // Try an array-only response next.
            }
        }
        int arrayStart = response.indexOf('[');
        int arrayEnd = response.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            try {
                return cleanTags(JSON.parseArray(response.substring(arrayStart, arrayEnd + 1).getBytes(StandardCharsets.UTF_8)));
            } catch (RuntimeException ignored) {
                // Fall through to an empty result.
            }
        }
        return List.of();
    }

    private List<String> parseTagsFromListFallback(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : response.lines().toList()) {
            String value = line.replaceFirst("^\\s*[-*\\d.、]+\\s*", "").trim();
            if (isMeaningfulTag(value)) {
                result.add(value);
            }
        }
        return dedupe(result);
    }

    private List<String> cleanTags(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : array) {
            if (value instanceof String text) {
                String tag = cleanTag(text);
                if (tag != null) {
                    result.add(tag);
                }
            }
        }
        return dedupe(result).stream().limit(20).toList();
    }

    private String cleanTag(String raw) {
        String value = raw == null ? "" : raw.trim().replaceFirst("^#+\\s*", "").replaceAll("^[\"'“”]+|[\"'“”]+$", "").trim();
        if (!isMeaningfulTag(value)) {
            return null;
        }
        return value;
    }

    private boolean isMeaningfulTag(String value) {
        return value.length() >= 2 && value.length() <= 24 && !FILE_EXTENSION.matcher(value).matches() && !CAMEL_CASE.matcher(value).find() && !PATH_OR_CODE.matcher(value)
                .find() && !SENTENCE_PUNCTUATION.matcher(value).find() && !value.matches("^(?:\\d+|[0-9a-fA-F]{8,})$");
    }

    private List<String> dedupe(List<String> tags) {
        Set<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            seen.add(tag);
        }
        return new ArrayList<>(seen);
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return result;
    }

    public record ImageInput(String mediaType, String base64) {
    }
}
