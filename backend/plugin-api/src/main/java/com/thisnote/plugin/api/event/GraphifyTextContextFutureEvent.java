package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 按导入内容查询 Graphify 中相关的概念与社区，不要求笔记已经存在。
 */
public record GraphifyTextContextFutureEvent(String text,
                                             CompletableFuture<GraphifyNoteContext> context)
        implements PluginFutureEvent<GraphifyNoteContext> {

    public GraphifyTextContextFutureEvent {
        text = text == null ? "" : text;
        context = Objects.requireNonNull(context, "context");
    }

    public static GraphifyTextContextFutureEvent request(String text) {
        return new GraphifyTextContextFutureEvent(text, new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<GraphifyNoteContext> result() {
        return context;
    }
}
