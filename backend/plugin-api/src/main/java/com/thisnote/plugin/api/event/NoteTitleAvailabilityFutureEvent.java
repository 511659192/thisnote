package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 快速探测是否有插件能够为导入笔记生成标题。
 */
public record NoteTitleAvailabilityFutureEvent(CompletableFuture<Boolean> available)
        implements PluginFutureEvent<Boolean> {

    public NoteTitleAvailabilityFutureEvent {
        available = Objects.requireNonNull(available, "available");
    }

    public static NoteTitleAvailabilityFutureEvent request() {
        return new NoteTitleAvailabilityFutureEvent(new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<Boolean> result() {
        return available;
    }
}
