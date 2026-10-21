package com.thisnote.ext.aititle.service;

import com.thisnote.ext.aititle.client.TitleAiClient;
import com.thisnote.ext.aititle.support.TitleHeuristics;
import com.thisnote.plugin.api.event.GraphifyNoteContext;
import com.thisnote.plugin.api.event.GraphifyTextContextFutureEvent;
import com.thisnote.plugin.api.event.NoteContentFutureEvent;
import com.thisnote.plugin.api.event.NoteTitleUpdateFutureEvent;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TitleSuggestionService {

    private static final long GRAPH_TIMEOUT_SECONDS = 10;
    private static final long QUERY_TIMEOUT_SECONDS = 30;
    private static final long RESULT_TIMEOUT_SECONDS = 130;
    private static final long DEADLINE_GRACE_MILLIS = 5_000;
    private static final int MAX_SEED_CHARS = 20_000;
    private static final int MAX_CANDIDATES = 3;

    private final TitleSettingsService settingsService;
    private final TitleAiClient aiClient;
    private final Map<String, PendingTask> pending = new ConcurrentHashMap<>();
    private final Map<String, List<String>> candidatesByNote = new ConcurrentHashMap<>();
    private final Map<String, String> currentTitleByNote = new ConcurrentHashMap<>();
    private final Map<String, String> errors = new ConcurrentHashMap<>();
    private ApplicationEventPublisher eventPublisher;

    public TitleSuggestionService(TitleSettingsService settingsService,
                                  TitleAiClient aiClient) {
        this.settingsService = settingsService;
        this.aiClient = aiClient;
    }

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
    }

    public SuggestionsView suggest(String noteId) {
        String id = requireNoteId(noteId);
        if (pending.containsKey(id)) {
            log.debug("AI 标题任务已存在：noteId={}", id);
            return view(id);
        }
        NoteCardDTO noteContent = noteContent(id);
        if (noteContent == null || noteContent.note() == null) {
            throw new IllegalArgumentException("笔记不存在：" + id);
        }
        String currentTitle = noteContent.note().title() == null ? "" : noteContent.note().title();
        String contentMd = content(noteContent.cards());
        String profileId = settingsService.requireAiProfileId();
        long deadlineEpochMs = System.currentTimeMillis()
                + RESULT_TIMEOUT_SECONDS * 1_000
                + DEADLINE_GRACE_MILLIS;
        PendingTask task = new PendingTask(deadlineEpochMs);
        if (pending.putIfAbsent(id, task) != null) {
            log.debug("AI 标题任务并发已存在：noteId={}", id);
            return view(id);
        }
        currentTitleByNote.put(id, currentTitle);
        candidatesByNote.remove(id);
        errors.remove(id);
        log.info("AI 标题任务已排队：noteId={}, currentTitle={}", id, currentTitle);
        try {
            GraphifyNoteContext context = contextOrEmpty(currentTitle + "\n" + contentMd);
            aiClient.requestCandidates(profileId, currentTitle, contentMd, context)
                    .orTimeout(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .whenComplete((response, error) -> finish(id, task, response, error));
        } catch (Exception e) {
            pending.remove(id, task);
            errors.put(id, safeMessage(e));
            log.error("AI 标题候选任务启动失败：noteId={}", id, e);
        }
        return view(id);
    }

    public SuggestionsView view(String noteId) {
        String id = requireNoteId(noteId);
        PendingTask task = pending.get(id);
        return new SuggestionsView(
                id,
                currentTitleByNote.getOrDefault(id, ""),
                candidatesByNote.getOrDefault(id, List.of()),
                task != null,
                task == null ? 0 : task.deadlineEpochMs(),
                errors.getOrDefault(id, "")
        );
    }

    public TitleUpdateResponse apply(String noteId,
                                     String title) {
        String id = requireNoteId(noteId);
        String candidate = title == null ? "" : title.trim();
        if (candidate.isEmpty()) {
            throw new IllegalArgumentException("标题不能为空");
        }
        NoteTitleUpdateFutureEvent request = NoteTitleUpdateFutureEvent.request(id, candidate);
        publisher().publishEvent(request);
        NoteDTO updated = join(request.result()
                .orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        log.info("AI 标题候选已应用：noteId={}, title={}", updated.noteId(), updated.title());
        return new TitleUpdateResponse(updated.noteId(), updated.title());
    }

    public String suggest(String fallbackTitle,
                          String contentMd) {
        if (TitleHeuristics.isMeaningful(fallbackTitle)) {
            log.debug("导入标题已有意义，保留原名：title={}", fallbackTitle);
            return fallbackTitle;
        }
        try {
            String profileId = settingsService.requireAiProfileId();
            String content = contentMd == null ? "" : contentMd;
            GraphifyNoteContext context = graphContext(fallbackTitle + "\n" + content);
            if (!context.available()) {
                log.info("Graphify 图谱不可用，保留导入原名：title={}", fallbackTitle);
                return fallbackTitle;
            }
            String generated = aiClient.generate(profileId, fallbackTitle, contentMd, context);
            if (!TitleHeuristics.isMeaningful(generated)) {
                log.warn("AI 标题结果不可用，保留导入原名：fallback={}, generated={}",
                        fallbackTitle, generated);
                return fallbackTitle;
            }
            log.info("AI 标题生成完成：fallback={}, title={}, concepts={}, communities={}",
                    fallbackTitle, generated, context.concepts().size(), context.communities().size());
            return generated;
        } catch (Exception e) {
            log.warn("AI 标题生成失败，保留导入原名：title={}", fallbackTitle, e);
            return fallbackTitle;
        }
    }

    private void finish(String noteId,
                        PendingTask task,
                        String response,
                        Throwable error) {
        try {
            if (error != null) {
                errors.put(noteId, safeMessage(error));
                log.error("AI 标题候选生成失败：noteId={}", noteId, error);
                return;
            }
            String currentTitle = currentTitleByNote.getOrDefault(noteId, "");
            List<String> candidates = TitleHeuristics.normalizeCandidates(response)
                    .stream()
                    .filter(candidate -> !candidate.equalsIgnoreCase(currentTitle.trim()))
                    .limit(MAX_CANDIDATES)
                    .toList();
            if (candidates.isEmpty()) {
                errors.put(noteId, "AI 没有返回可用的标题候选，请重试");
                log.warn("AI 标题候选为空：noteId={}", noteId);
                return;
            }
            candidatesByNote.put(noteId, candidates);
            errors.remove(noteId);
            log.info("AI 标题候选已生成：noteId={}, currentTitle={}, candidates={}",
                    noteId, currentTitle, candidates.size());
        } finally {
            pending.remove(noteId, task);
        }
    }

    private NoteCardDTO noteContent(String noteId) {
        NoteContentFutureEvent request = new NoteContentFutureEvent(noteId, new CompletableFuture<>());
        publisher().publishEvent(request);
        return join(request.result()
                .orTimeout(QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private GraphifyNoteContext contextOrEmpty(String text) {
        try {
            GraphifyNoteContext context = graphContext(text);
            if (context.available()) {
                return context;
            }
            log.info("Graphify 图谱不可用，使用笔记内容生成标题候选");
        } catch (Exception e) {
            log.warn("Graphify 文本上下文查询失败，使用笔记内容生成标题候选", e);
        }
        return new GraphifyNoteContext(false, List.of(), List.of(), List.of());
    }

    private GraphifyNoteContext graphContext(String text) {
        String seed = text == null ? "" : text;
        if (seed.length() > MAX_SEED_CHARS) {
            seed = seed.substring(0, MAX_SEED_CHARS);
        }
        GraphifyTextContextFutureEvent request = GraphifyTextContextFutureEvent.request(seed);
        publisher().publishEvent(request);
        return join(request.result()
                .orTimeout(GRAPH_TIMEOUT_SECONDS, TimeUnit.SECONDS));
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

    private static String content(List<CardDTO> cards) {
        if (cards == null || cards.isEmpty()) {
            return "";
        }
        return cards.stream()
                .map(CardDTO::content)
                .filter(Objects::nonNull)
                .filter(text -> !text.isBlank())
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        String message = cause.getMessage();
        return message == null || message.isBlank() ? "AI 标题生成失败" : message;
    }

    private ApplicationEventPublisher publisher() {
        if (eventPublisher == null) {
            throw new IllegalStateException("主容器消息管道尚未就绪");
        }
        return eventPublisher;
    }

    private static String requireNoteId(String noteId) {
        String value = noteId == null ? "" : noteId.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        return value;
    }

    private record PendingTask(long deadlineEpochMs) {
    }

    public record SuggestionsView(String noteId,
                                  String currentTitle,
                                  List<String> candidates,
                                  boolean pending,
                                  long deadlineEpochMs,
                                  String error) {
    }

    public record TitleUpdateResponse(String noteId,
                                      String title) {
    }
}
