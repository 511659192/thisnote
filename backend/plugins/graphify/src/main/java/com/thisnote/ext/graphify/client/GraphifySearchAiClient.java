package com.thisnote.ext.graphify.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.thisnote.ext.graphify.service.GraphifyGraphReader;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 把 Graphify 召回的候选笔记交给统一 AI 能力做语义重排。
 */
@Component
@Slf4j
public class GraphifySearchAiClient {

    private static final long RESULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TOKENS = 900;
    private static final int MAX_ANSWER_CHARS = 400;
    private static final int MAX_REASON_CHARS = 120;
    private static final String SEARCH_PROMPT = """
            你是个人笔记知识图谱的检索助手。用户会提出自然语言问题，候选笔记来自 Graphify
            生成的图谱，包含标题、概念和社区。只依据候选笔记中提供的信息判断相关性，不得编造
            候选外的事实、标题或笔记 ID。

            请完成两件事：
            1. 用一到两句简洁中文回答用户的问题，说明相关笔记覆盖了什么内容。
            2. 从候选中选择最相关的笔记，最多 5 篇，并为每篇给出不超过 40 字的匹配理由。

            如果没有相关笔记，answer 写“没有找到与该问题相关的笔记”，notes 返回空数组。
            只输出严格 JSON，不要 Markdown、代码围栏或额外说明：
            {"answer":"...","notes":[{"noteId":"候选中的 ID","reason":"..."}]}
            """;

    private ApplicationEventPublisher eventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public AiSearchResult search(String profileId,
                                 String query,
                                 List<GraphifyGraphReader.SearchCandidate> candidates) {
        AiCompletionFutureEvent request = AiCompletionFutureEvent.request(
                profileId,
                SEARCH_PROMPT,
                userText(query, candidates),
                List.of(),
                MAX_TOKENS
        );
        publisher().publishEvent(request);
        try {
            String response = request.result()
                    .orTimeout(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
            AiSearchResult result = parse(response);
            log.debug("Graphify NLP AI 查询完成：queryLength={}, candidates={}, notes={}",
                    query.length(), candidates.size(), result.notes().size());
            return result;
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw e;
        }
    }

    private static String userText(String query,
                                   List<GraphifyGraphReader.SearchCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：").append(query).append("\n\n候选笔记：\n");
        for (GraphifyGraphReader.SearchCandidate candidate : candidates) {
            builder.append("- noteId=").append(candidate.noteId())
                    .append(" | 标题=").append(candidate.title())
                    .append(" | 概念=").append(String.join("、", candidate.concepts()))
                    .append(" | 社区=").append(String.join("、", candidate.communities()))
                    .append('\n');
        }
        return builder.toString();
    }

    private static AiSearchResult parse(String response) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("AI 未返回查询结果");
        }
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            if (firstLine >= 0) {
                cleaned = cleaned.substring(firstLine + 1).trim();
            }
            if (cleaned.endsWith("```")) {
                cleaned = cleaned.substring(0, cleaned.length() - 3).trim();
            }
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.error("Graphify NLP AI 返回非 JSON 内容：length={}", cleaned.length());
            throw new IllegalStateException("AI 查询结果格式无效");
        }

        try {
            JSONObject payload = JSON.parseObject(cleaned.substring(start, end + 1));
            String answer = normalize(payload.getString("answer"), MAX_ANSWER_CHARS);
            List<AiSearchNote> notes = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            JSONArray selected = payload.getJSONArray("notes");
            if (selected != null) {
                for (Object value : selected) {
                    if (!(value instanceof JSONObject item)) {
                        continue;
                    }
                    String noteId = item.getString("noteId");
                    if (noteId == null || noteId.isBlank() || !seen.add(noteId.trim())) {
                        continue;
                    }
                    notes.add(new AiSearchNote(
                            noteId.trim(),
                            normalize(item.getString("reason"), MAX_REASON_CHARS)
                    ));
                }
            }
            return new AiSearchResult(answer, notes);
        } catch (Exception e) {
            log.error("Graphify NLP AI 返回结果解析失败：length={}", cleaned.length(), e);
            throw new IllegalStateException("AI 查询结果格式无效", e);
        }
    }

    private static String normalize(String value, int maxChars) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= maxChars
                ? normalized
                : normalized.substring(0, maxChars).strip();
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("主容器消息管道尚未就绪");
        }
        return eventPublisher;
    }

    public record AiSearchResult(String answer, List<AiSearchNote> notes) {
    }

    public record AiSearchNote(String noteId, String reason) {
    }
}
