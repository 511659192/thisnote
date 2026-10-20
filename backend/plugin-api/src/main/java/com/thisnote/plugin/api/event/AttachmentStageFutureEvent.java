package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 请求 notes 把本地图片复制到待管理附件目录，后续由定时导入任务处理。
 */
public record AttachmentStageFutureEvent(CompletableFuture<AttachmentStageResult> result,
                                         String sourcePath)
        implements PluginFutureEvent<AttachmentStageFutureEvent.AttachmentStageResult> {

    public AttachmentStageFutureEvent {
        result = Objects.requireNonNull(result, "result");
        sourcePath = sourcePath == null ? "" : sourcePath;
    }

    public static AttachmentStageFutureEvent request(String sourcePath) {
        return new AttachmentStageFutureEvent(new CompletableFuture<>(), sourcePath);
    }

    @Override
    public CompletableFuture<AttachmentStageResult> result() {
        return result;
    }

    /**
     * notes 暂存本地图片的结果。
     */
    public static record AttachmentStageResult(String fileName, boolean duplicated) {

        public AttachmentStageResult {
            fileName = fileName == null ? "" : fileName;
        }
    }
}
