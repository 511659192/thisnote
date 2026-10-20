package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 请求 notes 把本地 Markdown 复制到无主卡片目录，后续由定时导入任务处理。
 */
public record CardStageFutureEvent(CompletableFuture<CardStageResult> result,
                                   String sourcePath)
        implements PluginFutureEvent<CardStageFutureEvent.CardStageResult> {

    public CardStageFutureEvent {
        result = Objects.requireNonNull(result, "result");
        sourcePath = sourcePath == null ? "" : sourcePath;
    }

    public static CardStageFutureEvent request(String sourcePath) {
        return new CardStageFutureEvent(new CompletableFuture<>(), sourcePath);
    }

    @Override
    public CompletableFuture<CardStageResult> result() {
        return result;
    }

    /**
     * notes 暂存本地卡片的结果。
     */
    public static record CardStageResult(String fileName, boolean duplicated) {

        public CardStageResult {
            fileName = fileName == null ? "" : fileName;
        }
    }
}
