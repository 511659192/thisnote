package com.thisnote.ext.graphify.client;

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
public class GraphifyNotesClient {

    private static final int PAGE_SIZE = 500;
    private final HttpClient httpClient;
    private final String apiBase;

    public GraphifyNotesClient(@Value("${server.port:45917}") int serverPort) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.apiBase = "http://127.0.0.1:" + serverPort;
    }

    public List<NoteDTO> allNotes() {
        List<NoteDTO> notes = new ArrayList<>();
        int offset = 0;
        while (true) {
            String path = "/api/notes/get?limit=" + PAGE_SIZE + "&offset=" + offset;
            List<NoteDTO> page = getJson(path, new TypeReference<List<NoteDTO>>() { });
            if (page == null || page.isEmpty()) {
                return notes;
            }
            notes.addAll(page);
            if (page.size() < PAGE_SIZE) {
                return notes;
            }
            offset += page.size();
        }
    }

    public NoteDTO note(String noteId) {
        HttpResponse<String> response = getText("/api/notes/get-detail?noteId=" + encode(noteId));
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
        return getJson("/api/notes/cards/get?noteId=" + encode(noteId),
                new TypeReference<List<CardDTO>>() { });
    }

    public byte[] attachment(String fileName) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/api/notes/attachments/get?fileName=" + encode(fileName)))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) {
            return null;
        }
        ensureSuccess(response.statusCode());
        return response.body();
    }

    private <T> T getJson(String path, TypeReference<T> type) {
        HttpResponse<String> response = getText(path);
        ensureSuccess(response.statusCode());
        try {
            return JSON.parseObject(response.body(), type);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Notes API 返回的数据无法解析", e);
        }
    }

    private HttpResponse<String> getText(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
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
