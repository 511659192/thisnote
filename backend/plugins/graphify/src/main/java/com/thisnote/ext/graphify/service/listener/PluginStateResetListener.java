package com.thisnote.ext.graphify.service.listener;

import com.thisnote.ext.graphify.GraphifyPlugin;
import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.PluginResetResult;
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
        PluginStateResetEvent reset = event.getPayload();
        CompletableFuture<Void> future;
        try {
            future = indexService.resetState();
        } catch (RuntimeException e) {
            fail(reset, e);
            return;
        }
        if (future == null) {
            fail(reset, new IllegalStateException("异步重置未返回 future"));
            return;
        }
        future.whenComplete((ignored, error) -> {
            if (error == null) {
                if (reset.expects(GraphifyPlugin.PLUGIN_ID)) {
                    reset.complete(GraphifyPlugin.PLUGIN_ID,
                            PluginResetResult.reset(GraphifyPlugin.PLUGIN_ID, "Graphify 图谱与索引已清空"));
                }
            } else {
                fail(reset, error);
            }
        });
    }

    private void fail(PluginStateResetEvent reset,
                      Throwable error) {
        if (reset.expects(GraphifyPlugin.PLUGIN_ID)) {
            reset.complete(GraphifyPlugin.PLUGIN_ID,
                    PluginResetResult.failed(GraphifyPlugin.PLUGIN_ID, error));
        }
        log.warn("插件 {} 状态重置失败", GraphifyPlugin.PLUGIN_ID, error);
    }
}
