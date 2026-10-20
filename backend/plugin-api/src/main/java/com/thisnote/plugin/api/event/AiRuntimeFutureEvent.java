package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginFutureEvent;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 查询指定 AI profile 的运行配置。调用方不读取 AI 插件的配置文件。
 */
public record AiRuntimeFutureEvent(String profileId,
                                   CompletableFuture<Runtime> runtime)
        implements PluginFutureEvent<AiRuntimeFutureEvent.Runtime> {

    public AiRuntimeFutureEvent {
        profileId = Objects.requireNonNull(profileId, "profileId").trim();
        if (profileId.isEmpty()) {
            throw new IllegalArgumentException("profileId 不能为空");
        }
        runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static AiRuntimeFutureEvent request(String profileId) {
        return new AiRuntimeFutureEvent(profileId, new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<Runtime> result() {
        return runtime;
    }

    public record Runtime(String profileId,
                          String provider,
                          String model,
                          String endpoint,
                          String apiKey,
                          int maxTextChars) {

        public Runtime {
            profileId = profileId == null ? "" : profileId;
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
            endpoint = endpoint == null ? "" : endpoint;
            apiKey = apiKey == null ? "" : apiKey;
        }

        public boolean configured() {
            return !profileId.isBlank() && !provider.isBlank() && !model.isBlank()
                    && !endpoint.isBlank() && !apiKey.isBlank();
        }
    }
}
