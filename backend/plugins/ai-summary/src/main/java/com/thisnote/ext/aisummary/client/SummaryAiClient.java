package com.thisnote.ext.aisummary.client;

import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AttachmentBytesFutureEvent;
import com.thisnote.plugin.api.event.GraphifyNoteContext;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import com.thisnote.plugin.api.util.MarkdownImages;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class SummaryAiClient {

    private static final long RESULT_TIMEOUT_SECONDS = 130;
    private static final long ATTACHMENT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TOKENS = 600;
    private static final int MAX_SUMMARY_CHARS = 1200;
    private static final int MAX_NOTE_TEXT_CHARS = 40_000;
    private static final int MAX_IMAGES = 4;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int EMPTY_RESULT_RETRIES = 1;
    private static final String SUMMARY_PROMPT = """
            你是笔记编辑。请根据用户提供的笔记内容、图片和可用的知识图谱上下文，生成一段简洁的中文总结。
            第一句必须直接概括笔记解决的问题、核心技术结论或解决方案，不要写“该笔记”“截图为主体”
            “正文未附文字说明”之类关于载体、素材形式或内容完整性的描述。
            笔记只有截图或代码时，直接总结截图或代码表达的技术内容，不评价它为何种素材。
            如果提供了图片，必须直接阅读图片中的文字、代码、界面或结构，不要求用户另行补充文字说明。
            没有图谱上下文时只依据笔记内容和图片，不得提及图谱缺失。
            总结控制在 2 到 4 句、80 到 220 字，随后点明关键概念、主题或关联。
            不要使用标题、项目符号或 Markdown，不要编造未提供的事实，不要重复原文。
            """;

    private ApplicationEventPublisher eventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public String generate(String profileId,
                           NoteCardDTO content,
                           GraphifyNoteContext context) {
        String noteId = content.note() == null ? "" : content.note().noteId();
        String text = userText(content, context);
        List<AiCompletionFutureEvent.ImageInput> images = images(content.cards());
        for (int attempt = 0; attempt <= EMPTY_RESULT_RETRIES; attempt++) {
            AiCompletionFutureEvent request = AiCompletionFutureEvent.request(
                    profileId,
                    SUMMARY_PROMPT,
                    text,
                    images,
                    MAX_TOKENS
            );
            publisher().publishEvent(request);
            String response = request.result()
                    .orTimeout(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
            if (response != null && !response.isBlank()) {
                return normalize(response);
            }
            log.warn("AI 总结返回空内容：noteId={}, profileId={}, attempt={}/{}",
                    noteId, profileId, attempt + 1, EMPTY_RESULT_RETRIES + 1);
        }
        throw new IllegalStateException("AI 未返回总结内容");
    }

    private static String userText(NoteCardDTO content,
                                   GraphifyNoteContext context) {
        String title = content.note() == null || content.note().title() == null || content.note().title().isBlank()
                ? "未命名笔记"
                : content.note().title().trim();
        StringBuilder builder = new StringBuilder();
        builder.append("笔记标题：").append(title).append('\n');
        if (context.available()) {
            builder.append("图谱概念：").append(String.join("、", context.concepts())).append('\n');
            builder.append("图谱社区：").append(String.join("、", context.communities())).append('\n');
            if (!context.relatedNotes().isEmpty()) {
                builder.append("相关笔记：");
                builder.append(context.relatedNotes().stream()
                        .map(note -> note.title() + "（" + String.join("；", note.reasons()) + "）")
                        .reduce((left, right) -> left + "；" + right)
                        .orElse(""));
                builder.append('\n');
            }
        }
        builder.append("卡片正文：\n");
        String body = content.cards().stream()
                .filter(card -> card != null && card.content() != null)
                .map(CardDTO::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
        if (body.length() > MAX_NOTE_TEXT_CHARS) {
            body = body.substring(0, MAX_NOTE_TEXT_CHARS);
        }
        builder.append(body);
        return builder.toString();
    }

    private List<AiCompletionFutureEvent.ImageInput> images(List<CardDTO> cards) {
        List<AiCompletionFutureEvent.ImageInput> images = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (cards == null) {
            return images;
        }
        for (CardDTO card : cards) {
            if (card == null || card.content() == null) {
                continue;
            }
            for (MarkdownImages.Reference reference : MarkdownImages.attachmentReferences(card.content())) {
                if (images.size() >= MAX_IMAGES) {
                    return images;
                }
                String fileName = reference.fileName();
                if (!seen.add(fileName.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                try {
                    byte[] bytes = attachment(fileName);
                    if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                        log.debug("AI 总结跳过不可用图片：fileName={}, bytes={}",
                                fileName, bytes == null ? 0 : bytes.length);
                        continue;
                    }
                    images.add(new AiCompletionFutureEvent.ImageInput(
                            mediaType(fileName),
                            Base64.getEncoder().encodeToString(bytes)
                    ));
                } catch (Exception e) {
                    log.warn("AI 总结读取图片失败，跳过：fileName={}", fileName, e);
                }
            }
        }
        return images;
    }

    private byte[] attachment(String fileName) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        publisher().publishEvent(new AttachmentBytesFutureEvent(fileName, result));
        return result.orTimeout(ATTACHMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS).join();
    }

    private static String normalize(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("AI 未返回总结内容");
        }
        String summary = response.strip().replaceAll("\\s+", " ");
        return summary.length() <= MAX_SUMMARY_CHARS
                ? summary
                : summary.substring(0, MAX_SUMMARY_CHARS).strip() + "...";
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("主容器消息管道尚未就绪");
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
