package com.thisnote.ext.ai.service;

import com.thisnote.ext.ai.client.AiProviderClient;
import com.thisnote.plugin.api.event.AttachmentBytesQueryEvent;
import com.thisnote.plugin.api.event.AiRuntimeEvent;
import com.thisnote.plugin.api.event.NoteContentQueryEvent;
import com.thisnote.plugin.api.event.NoteTagSuggestionEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AiTagSuggestionService {

    private static final int MAX_IMAGES = 6;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final long QUERY_TIMEOUT_SECONDS = 30;
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\((<[^>]+>|(?:\\\\.|[^)])*)\\)");
    private static final Pattern ATTACHMENT_TARGET = Pattern.compile(
            "(?i)(?:\\.\\./)?attachments/(.+?\\.(?:png|jpg|jpeg|webp))(?:[?#].*)?$");

    private final AiSettingsService settingsService;
    private final AiProviderClient providerClient;
    private ApplicationEventPublisher eventPublisher;

    public AiTagSuggestionService(AiSettingsService settingsService,
                                  AiProviderClient providerClient) {
        this.settingsService = settingsService;
        this.providerClient = providerClient;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void suggest(NoteTagSuggestionEvent event) {
        try {
            AiRuntimeEvent.Runtime runtime = settingsService.runtime();
            if (!runtime.configured()) {
                event.suggestions().complete(List.of());
                return;
            }
            NoteCardDTO content = noteContent(event.noteId());
            if (content == null || content.note() == null) {
                event.suggestions().complete(List.of());
                return;
            }
            NoteDTO note = content.note();
            List<CardDTO> cards = content.cards();
            int maxTextChars = settingsService.settings().maxTextChars();
            String text = noteText(note, cards, maxTextChars);
            List<AiProviderClient.ImageInput> images = images(cards);
            List<String> tags = providerClient.suggestTags(runtime, text, images);
            event.suggestions().complete(tags.stream().limit(event.limit()).toList());
        } catch (Exception e) {
            log.error("AI 标签建议失败：noteId={}", event.noteId(), e);
            event.suggestions().complete(List.of());
        }
    }

    private String noteText(NoteDTO note, List<CardDTO> cards, int maxTextChars) {
        StringBuilder text = new StringBuilder("# ")
                .append(note.title() == null ? "" : note.title())
                .append("\n\n");
        if (cards != null) {
            for (CardDTO card : cards) {
                if (card != null && card.content() != null) {
                    text.append(card.content()).append("\n\n");
                }
            }
        }
        return text.length() <= maxTextChars ? text.toString() : text.substring(0, maxTextChars);
    }

    private List<AiProviderClient.ImageInput> images(List<CardDTO> cards) {
        List<AiProviderClient.ImageInput> images = new ArrayList<>();
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
                images.add(new AiProviderClient.ImageInput(
                        mediaType(fileName),
                        Base64.getEncoder().encodeToString(bytes)
                ));
            }
        }
        return images;
    }

    private NoteCardDTO noteContent(String noteId) {
        CompletableFuture<NoteCardDTO> result = new CompletableFuture<>();
        publisher().publishEvent(new NoteContentQueryEvent(noteId, result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private byte[] attachment(String fileName) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        publisher().publishEvent(new AttachmentBytesQueryEvent(fileName, result));
        return result.orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
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
