package com.thisnote.ext.tags.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AttachmentBytesFutureEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class TagSuggestionService {

    private static final int MAX_IMAGES = 6;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final long QUERY_TIMEOUT_SECONDS = 30;
    private static final int MAX_TOKENS = 1200;
    private static final String TAG_PROMPT = """
            Extract 10 to 20 high-level, reusable business tags from the note text and images.
            Return JSON only in the form {"tags":["tag"]}.
            Tags must be short concepts, not section headings, file names, code symbols, URLs, or full sentences.
            Prefer concepts that help find the note later. Deduplicate and keep every tag under 24 characters.
            Use the note's dominant language for the tag labels.
            """;
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\((<[^>]+>|(?:\\\\.|[^)])*)\\)");
    private static final Pattern ATTACHMENT_TARGET = Pattern.compile(
            "(?i)(?:\\.\\./)?attachments/(.+?\\.(?:png|jpg|jpeg|webp))(?:[?#].*)?$");
    private static final Pattern FILE_EXTENSION = Pattern.compile(".*\\.(?:java|md|json|yml|yaml|xml|ts|tsx|js|jsx|css|html|properties)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CAMEL_CASE = Pattern.compile("[a-z][A-Z]");
    private static final Pattern PATH_OR_CODE = Pattern.compile("[/\\\\`()\\[\\]{}<>=|$]");
    private static final Pattern SENTENCE_PUNCTUATION = Pattern.compile("[，,、;；:：。.!?！？]");

    private ApplicationEventPublisher eventPublisher;

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
            String response = complete(TagTextCounter.noteText(note, cards), images(cards));
            return parseTags(response).stream().limit(limit).toList();
        } catch (Exception e) {
            log.error("AI 标签建议失败：noteId={}", note.noteId(), e);
            return List.of();
        }
    }

    private String complete(String text,
                            List<AiCompletionFutureEvent.ImageInput> images) {
        AiCompletionFutureEvent request = AiCompletionFutureEvent.request(TAG_PROMPT, text, images, MAX_TOKENS);
        publisher().publishEvent(request);
        return request.completion().isDone() ? request.completion().getNow("") : "";
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
            Matcher matcher = MARKDOWN_IMAGE.matcher(card.content());
            while (matcher.find() && images.size() < MAX_IMAGES) {
                String rawTarget = matcher.group(1).trim();
                String target = rawTarget.startsWith("<") && rawTarget.endsWith(">")
                        ? rawTarget.substring(1, rawTarget.length() - 1)
                        : rawTarget;
                Matcher attachment = ATTACHMENT_TARGET.matcher(target);
                if (!attachment.matches()) {
                    continue;
                }
                String fileName = attachment.group(1).replace("%20", " ");
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
                .map(line -> line.replaceFirst("^\\s*[-*\\d.、]+\\s*", "").trim())
                .filter(this::isMeaningfulTag)
                .distinct()
                .toList();
    }

    private List<String> cleanTags(JSONArray array) {
        if (array == null) {
            return List.of();
        }
        return array.stream().filter(value -> value instanceof String).map(String::valueOf).map(this::cleanTag).filter(Objects::nonNull).distinct().limit(20).toList();
    }

    private String cleanTag(String raw) {
        String value = raw == null ? "" : raw.trim().replaceFirst("^#+\\s*", "").replaceAll("^[\"'“”]+|[\"'“”]+$", "").trim();
        return isMeaningfulTag(value) ? value : null;
    }

    private boolean isMeaningfulTag(String value) {
        return value.length() >= 2 && value.length() <= 24 && !FILE_EXTENSION.matcher(value).matches() && !CAMEL_CASE.matcher(value).find()
                && !PATH_OR_CODE.matcher(value).find() && !SENTENCE_PUNCTUATION.matcher(value).find()
                && !value.matches("^(?:\\d+|[0-9a-fA-F]{8,})$");
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
