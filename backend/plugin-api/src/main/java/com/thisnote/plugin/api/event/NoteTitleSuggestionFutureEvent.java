package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 为导入笔记生成更有意义的标题；无响应方时由调用方保留原名。
 */
public record NoteTitleSuggestionFutureEvent(String fallbackTitle,
                                             String contentMd,
                                             CompletableFuture<String> suggestedTitle)
        implements PluginFutureEvent<String> {

    public NoteTitleSuggestionFutureEvent {
        fallbackTitle = Objects.requireNonNull(fallbackTitle, "fallbackTitle").trim();
        if (fallbackTitle.isEmpty()) {
            throw new IllegalArgumentException("fallbackTitle 不能为空");
        }
        contentMd = contentMd == null ? "" : contentMd;
        suggestedTitle = Objects.requireNonNull(suggestedTitle, "suggestedTitle");
    }

    public static NoteTitleSuggestionFutureEvent request(String fallbackTitle,
                                                         String contentMd) {
        return new NoteTitleSuggestionFutureEvent(
                fallbackTitle, contentMd, new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<String> result() {
        return suggestedTitle;
    }
}
