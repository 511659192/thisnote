package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginQuery;

import java.util.concurrent.CompletableFuture;

/**
 * 按文件名读取附件字节，由 notes 侧监听填充；不存在时返回 null。
 */
public record AttachmentBytesQueryEvent(String fileName,
                                        CompletableFuture<byte[]> result)
        implements PluginQuery<byte[]> {

    public AttachmentBytesQueryEvent {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName 不能为空");
        }
        if (result == null) {
            throw new IllegalArgumentException("result 不能为空");
        }
    }
}
