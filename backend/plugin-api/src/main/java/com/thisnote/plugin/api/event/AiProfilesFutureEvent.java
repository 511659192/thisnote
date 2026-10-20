package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 查询可用的 AI completion profile 摘要。
 */
public record AiProfilesFutureEvent(CompletableFuture<List<AiProfileSummary>> profiles)
        implements PluginFutureEvent<List<AiProfileSummary>> {

    public static final String DEFAULT_PROFILE_ID = "default-chat";

    public AiProfilesFutureEvent {
        profiles = Objects.requireNonNull(profiles, "profiles");
    }

    public static AiProfilesFutureEvent request() {
        return new AiProfilesFutureEvent(new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<List<AiProfileSummary>> result() {
        return profiles;
    }
}
