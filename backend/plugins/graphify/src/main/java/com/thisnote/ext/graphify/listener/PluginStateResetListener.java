package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.GraphifyPlugin;
import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.util.concurrent.CompletableFuture;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        CompletableFuture<Void> future;
        try {
            future = indexService.resetState();
        } catch (Exception e) {
            fail(e);
            return;
        }
        if (future == null) {
            fail(new IllegalStateException("异步重置未返回 future"));
            return;
        }
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                fail(error);
            }
        });
    }

    private void fail(Throwable error) {
        log.error("插件 {} 状态重置失败", GraphifyPlugin.PLUGIN_ID, error);
    }
}
