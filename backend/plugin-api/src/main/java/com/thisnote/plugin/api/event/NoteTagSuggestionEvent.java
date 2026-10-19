package com.thisnote.plugin.api.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 请求可选语义提供者给出笔记的高层标签建议。
 *
 * <p>请求方负责保存标签；提供方只读取自己的索引并完成 future，双方不共享存储。
 * 没有提供者时 future 保持未完成，请求方应立即走本地回退。
 */
public record NoteTagSuggestionEvent(String noteId,
                                     int limit,
                                     CompletableFuture<List<String>> suggestions) {

    public NoteTagSuggestionEvent {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        noteId = noteId.trim();
        limit = Math.min(limit, 50);
        suggestions = Objects.requireNonNull(suggestions, "suggestions");
    }

    public static NoteTagSuggestionEvent request(String noteId, int limit) {
        return new NoteTagSuggestionEvent(noteId, limit, new CompletableFuture<>());
    }
}
