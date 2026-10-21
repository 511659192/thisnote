package com.thisnote.ext.graphify.service;

import com.thisnote.ext.graphify.client.GraphifySearchAiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排 Graphify 图谱召回与 AI 语义重排，提供只读自然语言查询。
 */
@Service
@Slf4j
public class GraphifyNlpSearchService {

    private static final int CANDIDATE_LIMIT = 24;
    private static final int RESULT_LIMIT = 5;
    private static final int MAX_QUERY_CHARS = 200;

    private final GraphifyGraphReader graphReader;
    private final GraphifySettingsService settingsService;
    private final GraphifySearchAiClient searchAiClient;

    public GraphifyNlpSearchService(GraphifyGraphReader graphReader,
                                    GraphifySettingsService settingsService,
                                    GraphifySearchAiClient searchAiClient) {
        this.graphReader = graphReader;
        this.settingsService = settingsService;
        this.searchAiClient = searchAiClient;
    }

    public SearchResponse search(String query) {
        String normalizedQuery = query == null ? "" : query.strip();
        if (normalizedQuery.length() < 2) {
            throw new IllegalArgumentException("请输入至少 2 个字符的查询");
        }
        if (normalizedQuery.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException("查询不能超过 " + MAX_QUERY_CHARS + " 个字符");
        }

        GraphifyGraphReader.SearchCandidates candidates =
                graphReader.searchCandidates(normalizedQuery, CANDIDATE_LIMIT);
        if (!candidates.available()) {
            return new SearchResponse(false, "", List.of());
        }
        if (candidates.notes().isEmpty()) {
            return new SearchResponse(true, "图谱中没有可供查询的笔记。", List.of());
        }

        String profileId = settingsService.selectedProfileId();
        if (profileId.isBlank()) {
            log.warn("Graphify NLP 查询失败：尚未选择 AI profile");
            throw new IllegalStateException("请先在 Graphify 设置中选择 AI profile");
        }

        GraphifySearchAiClient.AiSearchResult aiResult;
        try {
            aiResult = searchAiClient.search(profileId, normalizedQuery, candidates.notes());
        } catch (RuntimeException e) {
            log.error("Graphify NLP 查询失败：queryLength={}, candidates={}, aiProfileId={}",
                    normalizedQuery.length(), candidates.notes().size(), profileId, e);
            throw e;
        }

        Map<String, GraphifyGraphReader.SearchCandidate> candidatesById = new LinkedHashMap<>();
        candidates.notes().forEach(candidate -> candidatesById.put(candidate.noteId(), candidate));
        List<SearchResult> results = new ArrayList<>();
        for (GraphifySearchAiClient.AiSearchNote note : aiResult.notes()) {
            GraphifyGraphReader.SearchCandidate candidate = candidatesById.get(note.noteId());
            if (candidate == null) {
                log.warn("Graphify NLP AI 返回候选外笔记，已忽略：noteId={}", note.noteId());
                continue;
            }
            String reason = note.reason().isBlank() ? "图谱语义匹配" : note.reason();
            results.add(new SearchResult(candidate.noteId(), candidate.title(), reason));
            if (results.size() >= RESULT_LIMIT) {
                break;
            }
        }
        String answer = aiResult.answer().isBlank()
                ? (results.isEmpty() ? "没有找到与该问题相关的笔记。" : "根据图谱找到以下相关笔记。")
                : aiResult.answer();
        log.info("Graphify NLP 查询完成：queryLength={}, candidates={}, results={}, aiProfileId={}",
                normalizedQuery.length(), candidates.notes().size(), results.size(), profileId);
        return new SearchResponse(true, answer, results);
    }

    public record SearchResponse(boolean available, String answer, List<SearchResult> notes) {
    }

    public record SearchResult(String noteId, String title, String reason) {
    }
}
