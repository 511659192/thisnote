package com.thisnote.ext.aititle.client;

import com.thisnote.ext.aititle.support.TitleHeuristics;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AttachmentBytesFutureEvent;
import com.thisnote.plugin.api.event.GraphifyNoteContext;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class TitleAiClient {

    private static final long RESULT_TIMEOUT_SECONDS = 60;
    private static final long ATTACHMENT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TOKENS = 64_000;
    private static final int MAX_IMAGES = 3;
    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_CONTENT_CHARS = 30_000;
    private static final String TITLE_PROMPT = """
            你是笔记标题编辑器。根据笔记内容和 Graphify 知识图谱上下文，只输出一个有意义、具体、
            可检索的中文笔记标题。标题控制在 6 到 28 个字符，提炼内容表达的技术主题或解决方案；
            不要描述“截图”“图片”“文件”“笔记”等载体，不要输出文件名、扩展名、哈希、UUID、
            Markdown、引号、解释或句末标点。图谱概念与社区中的准确术语可以优先使用。
            """;
    private static final String CANDIDATE_PROMPT = """
            你是笔记标题编辑器。根据笔记内容和 Graphify 知识图谱上下文，输出 3 个有意义、具体、
            可检索的中文标题候选。每个标题控制在 6 到 28 个字符，提炼内容表达的技术主题或解决方案；
            每行只输出一个标题，不要编号、引号、解释或句末标点。不要描述“截图”“图片”“文件”“笔记”
            等载体，不要输出文件名、扩展名、哈希、UUID 或 Markdown。图谱术语可以优先使用。
            """;

    private ApplicationEventPublisher eventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public String generate(String profileId,
                           String currentTitle,
                           String contentMd,
                           GraphifyNoteContext context) {
        String response = complete(profileId, TITLE_PROMPT, currentTitle, contentMd, context);
        return TitleHeuristics.normalizeGenerated(response);
    }

    public CompletableFuture<String> requestCandidates(String profileId,
                                                       String currentTitle,
                                                       String contentMd,
                                                       GraphifyNoteContext context) {
        AiCompletionFutureEvent request = AiCompletionFutureEvent.request(
                profileId,
                CANDIDATE_PROMPT,
                userText(currentTitle, contentMd, context),
                images(contentMd),
                MAX_TOKENS
        );
        publisher().publishEvent(request);
        return request.result();
    }

    private String complete(String profileId,
                            String prompt,
                            String currentTitle,
                            String contentMd,
                            GraphifyNoteContext context) {
        AiCompletionFutureEvent request = AiCompletionFutureEvent.request(
                profileId,
                prompt,
                userText(currentTitle, contentMd, context),
                images(contentMd),
                MAX_TOKENS
        );
        publisher().publishEvent(request);
        return join(request.result()
                .orTimeout(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private static String userText(String currentTitle,
                                   String contentMd,
                                   GraphifyNoteContext context) {
        StringBuilder builder = new StringBuilder();
        builder.append("当前标题：").append(currentTitle).append('\n');
        builder.append("图谱概念：").append(String.join("、", context.concepts())).append('\n');
        builder.append("图谱社区：").append(String.join("、", context.communities())).append('\n');
        builder.append("笔记内容：\n");
        String content = contentMd == null ? "" : contentMd;
        builder.append(content.length() <= MAX_CONTENT_CHARS
                ? content
                : content.substring(0, MAX_CONTENT_CHARS));
        return builder.toString();
    }

    private List<AiCompletionFutureEvent.ImageInput> images(String contentMd) {
        List<AiCompletionFutureEvent.ImageInput> images = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MarkdownImages.Reference reference : MarkdownImages.attachmentReferences(contentMd)) {
            if (images.size() >= MAX_IMAGES) {
                break;
            }
            String fileName = reference.fileName();
            if (!seen.add(fileName.toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                byte[] bytes = attachment(fileName);
                if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    log.debug("AI 标题跳过不可用图片：fileName={}, bytes={}",
                            fileName, bytes == null ? 0 : bytes.length);
                    continue;
                }
                images.add(new AiCompletionFutureEvent.ImageInput(
                        mediaType(fileName),
                        Base64.getEncoder().encodeToString(bytes)
                ));
            } catch (Exception e) {
                log.warn("AI 标题读取图片失败，跳过：fileName={}", fileName, e);
            }
        }
        return images;
    }

    private byte[] attachment(String fileName) {
        CompletableFuture<byte[]> result = new CompletableFuture<>();
        publisher().publishEvent(new AttachmentBytesFutureEvent(fileName, result));
        return join(result.orTimeout(ATTACHMENT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("主容器消息管道尚未就绪");
        }
        return eventPublisher;
    }

    private static <T> T join(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static String mediaType(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
