package com.thisnote.ext.statereset.service;

import com.thisnote.ext.statereset.StateResetPlugin;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import com.thisnote.plugin.api.event.PluginStateResetReport;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.PluginManager;
import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Resets active plugins through their own listeners. Inactive plugins are skipped
 * because reset targets are no longer part of the static plugin metadata.
 */
@Slf4j
@Service
public class StateResetService {

    private static final long ACTIVE_RESET_TIMEOUT_SECONDS = 30L;

    private ApplicationEventPublisher mainEventPublisher;
    private PluginManager pluginManager;

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    public void setPluginManager(PluginManager pluginManager) {
        this.pluginManager = Objects.requireNonNull(pluginManager);
    }

    public PluginStateResetReport resetAll() {
        if (pluginManager == null) {
            throw new IllegalStateException("插件管理器尚未就绪");
        }

        List<PluginWrapper> wrappers = List.copyOf(pluginManager.getPlugins());
        Map<String, CompletableFuture<PluginResetResult>> participants = new LinkedHashMap<>();
        for (PluginWrapper wrapper : wrappers) {
            if (wrapper.getPluginState() == PluginState.STARTED) {
                participants.put(wrapper.getPluginId(), new CompletableFuture<>());
            }
        }

        mainEventPublisher.publishEvent(new PluginStateResetEvent(StateResetPlugin.PLUGIN_ID, participants));

        Map<String, PluginResetResult> inactiveResults = new LinkedHashMap<>();
        for (PluginWrapper wrapper : wrappers) {
            String pluginId = wrapper.getPluginId();
            if (!participants.containsKey(pluginId)) {
                inactiveResults.put(pluginId, PluginResetResult.skipped(pluginId, "插件未运行，未执行状态清理"));
            }
        }
        Map<String, CompletableFuture<PluginResetResult>> settled = new LinkedHashMap<>();
        participants.forEach((pluginId, future) ->
                settled.put(pluginId, future
                        .orTimeout(ACTIVE_RESET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .exceptionally(error -> {
                            log.error("插件 {} 状态重置未完成", pluginId, error);
                            return PluginResetResult.failed(pluginId, timeoutMessage(error));
                        })));
        settled.values().forEach(future -> future.join());

        List<PluginResetResult> results = new ArrayList<>();
        for (PluginWrapper wrapper : wrappers) {
            String pluginId = wrapper.getPluginId();
            PluginResetResult active = settled.get(pluginId) == null ? null : settled.get(pluginId).join();
            results.add(active == null ? inactiveResults.get(pluginId) : active);
        }
        results.sort(Comparator.comparing(PluginResetResult::pluginId));
        PluginStateResetReport report = new PluginStateResetReport(true, results);
        log.info("插件状态重置完成：ok={}, results={}", report.ok(), report.results().size());
        return report;
    }

    private static String timeoutMessage(Throwable error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        if (cause instanceof java.util.concurrent.TimeoutException) {
            return "状态重置超时";
        }
        return safeMessage(cause);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? "未知错误" : message;
    }
}
