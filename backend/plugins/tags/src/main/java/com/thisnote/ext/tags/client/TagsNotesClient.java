package com.thisnote.ext.tags.client;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.thisnote.plugin.api.notes.dto.CardDTO;
import com.thisnote.plugin.api.notes.dto.NoteDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
public class TagsNotesClient {

    private final HttpClient httpClient;
    private final String apiBase;

    public TagsNotesClient(@Value("${server.port:45917}") int serverPort) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.apiBase = "http://127.0.0.1:" + serverPort;
    }

    public NoteDTO note(String noteId) {
        HttpResponse<String> response = get("/api/notes/get-detail?noteId=" + encode(noteId));
        if (response.statusCode() == 404) {
            return null;
        }
        ensureSuccess(response.statusCode());
        try {
            return JSON.parseObject(response.body(), NoteDTO.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Notes API 返回的笔记数据无法解析", e);
        }
    }

    public List<CardDTO> cards(String noteId) {
        HttpResponse<String> response = get("/api/notes/cards/get?noteId=" + encode(noteId));
        ensureSuccess(response.statusCode());
        try {
            return JSON.parseObject(response.body(), new TypeReference<List<CardDTO>>() { });
        } catch (RuntimeException e) {
            throw new IllegalStateException("Notes API 返回的卡片数据无法解析", e);
        }
    }

    public List<NoteDTO> allNotes() {
        List<NoteDTO> result = new ArrayList<>();
        int offset = 0;
        int pageSize = 500;
        while (true) {
            HttpResponse<String> response = get("/api/notes/get?limit=" + pageSize + "&offset=" + offset);
            ensureSuccess(response.statusCode());
            List<NoteDTO> page;
            try {
                page = JSON.parseObject(response.body(), new TypeReference<List<NoteDTO>>() { });
            } catch (RuntimeException e) {
                throw new IllegalStateException("Notes API 返回的笔记列表无法解析", e);
            }
            if (page == null || page.isEmpty()) {
                break;
            }
            result.addAll(page);
            if (page.size() < pageSize) {
                break;
            }
            offset += page.size();
        }
        return result;
    }

    private HttpResponse<String> get(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 Notes API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取 Notes API 被中断", e);
        }
    }

    private static void ensureSuccess(int status) {
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Notes API 返回 HTTP " + status);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
