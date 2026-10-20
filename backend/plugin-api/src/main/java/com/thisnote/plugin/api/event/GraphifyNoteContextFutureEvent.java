package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.concurrent.CompletableFuture;

/**
 * 查询单篇笔记在 Graphify 知识图谱中的上下文。
 */
public record GraphifyNoteContextFutureEvent(String noteId,
                                             CompletableFuture<GraphifyNoteContext> context)
        implements PluginFutureEvent<GraphifyNoteContext> {

    public GraphifyNoteContextFutureEvent {
        if (noteId == null || noteId.isBlank()) {
            throw new IllegalArgumentException("noteId 不能为空");
        }
        if (context == null) {
            throw new IllegalArgumentException("context 不能为空");
        }
    }

    public static GraphifyNoteContextFutureEvent request(String noteId) {
        return new GraphifyNoteContextFutureEvent(noteId, new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<GraphifyNoteContext> result() {
        return context;
    }
}
