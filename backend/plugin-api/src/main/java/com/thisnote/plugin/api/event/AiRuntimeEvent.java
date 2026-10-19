package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginQuery;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 查询 AI 能力插件当前运行配置。调用方不读取 AI 插件的配置文件。
 */
public record AiRuntimeEvent(CompletableFuture<Runtime> runtime)
        implements PluginQuery<AiRuntimeEvent.Runtime> {

    public AiRuntimeEvent {
        runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public static AiRuntimeEvent request() {
        return new AiRuntimeEvent(new CompletableFuture<>());
    }

    @Override
    public CompletableFuture<Runtime> result() {
        return runtime;
    }

    public record Runtime(String provider, String model, String apiKey) {

        public Runtime {
            provider = provider == null ? "" : provider;
            model = model == null ? "" : model;
            apiKey = apiKey == null ? "" : apiKey;
        }

        public boolean configured() {
            return !provider.isBlank() && !model.isBlank() && !apiKey.isBlank();
        }
    }
}
