package com.thisnote.ext.tags.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AttachmentBytesFutureEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.util.MarkdownImages;
import com.thisnote.ext.tags.service.TagsSettingsService;
import com.thisnote.ext.tags.support.TagRules;
import com.thisnote.ext.tags.support.TagTextCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TagAiClient {

    private static final int MAX_IMAGES = 6;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final long QUERY_TIMEOUT_SECONDS = 30;
    public static final long RESULT_TIMEOUT_SECONDS = 130;
    private static final int MAX_TOKENS = 1200;
    private static final String TAG_PROMPT = """
            Extract 10 to 20 high-level, reusable business tags from the note text and images.
            Return JSON only in the form {"tags":["tag"]}.
            Tags must be short concepts, not section headings, file names, code symbols, URLs, or full sentences.
            Prefer concepts that help find the note later. Deduplicate and keep every tag under 24 characters.
            Use the note's dominant language for the tag labels.
            """;
    private final TagsSettingsService settingsService;
    private ApplicationEventPublisher eventPublisher;

    public TagAiClient(TagsSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public List<String> suggest(NoteDTO note,
                                List<CardDTO> cards,
                                int limit) {
        if (note == null || limit <= 0) {
            return List.of();
        }
        try {
            String text = TagTextCounter.noteText(note, cards);
            List<AiCompletionFutureEvent.ImageInput> images = images(cards);
            log.debug("请求 AI 标签建议：noteId={}, textLength={}, images={}",
                    note.noteId(), text.length(), images.size());
            String response = complete(text, images);
            List<String> tags = parseTags(response).stream().limit(limit).toList();
            log.debug("AI 标签建议完成：noteId={}, tags={}", note.noteId(), tags.size());
            return tags;
        } catch (Exception e) {
            log.error("AI 标签建议失败：noteId={}", note.noteId(), e);
            return List.of();
        }
    }

    private String complete(String text,
                            List<AiCompletionFutureEvent.ImageInput> images) {
        AiCompletionFutureEvent request = AiCompletionFutureEvent.request(
                settingsService.aiProfileId(),
                TAG_PROMPT,
                text,
                images,
                MAX_TOKENS
        );
        publisher().publishEvent(request);
        return request.completion().orTimeout(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private List<AiCompletionFutureEvent.ImageInput> images(List<CardDTO> cards) {
        List<AiCompletionFutureEvent.ImageInput> images = new ArrayList<>();
        if (cards == null) {
            return images;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (CardDTO card : cards) {
            if (card == null || card.content() == null) {
                continue;
            }
            for (MarkdownImages.Reference reference : MarkdownImages.attachmentReferences(card.content())) {
                if (images.size() >= MAX_IMAGES) {
                    break;
                }
                String fileName = reference.fileName();
                if (!seen.add(fileName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                byte[] bytes = attachment(fileName);
                if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    continue;
                }
                images.add(new AiCompletionFutureEvent.ImageInput(
                        mediaType(fileName),
                        Base64.getEncoder().encodeToString(bytes)
                ));
            }
        }
        return images;
    }

    private byte[] attachment(String fileName) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        publisher().publishEvent(new AttachmentBytesFutureEvent(fileName, result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private List<String> parseTags(String response) {
        if (response == null || response.isBlank()) {
            return List.of();
        }
        List<String> tags = parseJsonTags(response);
        return tags.isEmpty() ? parseTagsFromListFallback(response) : tags;
    }

    private List<String> parseJsonTags(String response) {
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JSONObject object = JSON.parseObject(response.substring(start, end + 1).getBytes(StandardCharsets.UTF_8));
                return cleanTags(object.getJSONArray("tags"));
            } catch (Exception e) {
                log.error("AI 标签响应无法解析为 JSON 对象", e);
            }
        }
        int arrayStart = response.indexOf('[');
        int arrayEnd = response.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            try {
                return cleanTags(JSON.parseArray(response.substring(arrayStart, arrayEnd + 1).getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                log.error("AI 标签响应无法解析为 JSON 数组", e);
            }
        }
        return List.of();
    }

    private List<String> parseTagsFromListFallback(String response) {
        return response.lines()
                .map(TagRules::clean)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private List<String> cleanTags(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        return array.stream().filter(value -> value instanceof String).map(String::valueOf).map(TagRules::clean).filter(Objects::nonNull).distinct().limit(20).toList();
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("Notes 消息管道尚未就绪");
        }
        return eventPublisher;
    }

    private static String mediaType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
