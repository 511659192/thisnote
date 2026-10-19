package com.thisnote.ext.graphify;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.ext.graphify.client.GraphifyAiClient;
import com.thisnote.plugin.api.event.AttachmentChangedEvent;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class GraphifyPlugin extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "graphify";

    private GraphifyIndexService indexService;

    public GraphifyPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, GraphifyStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        indexService = ctx.getBean(GraphifyIndexService.class);
        ctx.getBean(GraphifyAiClient.class).setMainEventPublisher(getMainApplicationContext());
        getMainApplicationContext().addApplicationListener(this);
        log.info("Graphify 插件已启动并注册笔记、卡片和附件监听器");
    }

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return eventType.toClass() == PayloadApplicationEvent.class;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!(event instanceof PayloadApplicationEvent<?> payloadEvent)) {
            return;
        }
        Object payload = payloadEvent.getPayload();
        if (payload instanceof NoteChangedEvent change) {
            indexService.onNoteChanged(change);
        } else if (payload instanceof CardChangedEvent change) {
            indexService.onCardChanged(change);
        } else if (payload instanceof AttachmentChangedEvent change) {
            indexService.onAttachmentChanged(change);
        } else if (payload instanceof PluginStateResetEvent resetEvent) {
            CompletableFuture<Void> future;
            try {
                future = indexService.resetState();
            } catch (RuntimeException e) {
                completeResetFailure(resetEvent, e);
                return;
            }
            if (future == null) {
                completeResetFailure(resetEvent, new IllegalStateException("异步重置未返回 future"));
                return;
            }
            future.whenComplete((ignored, error) -> {
                if (error == null) {
                    completeResetSuccess(resetEvent);
                } else {
                    completeResetFailure(resetEvent, error);
                }
            });
        }
    }

    private void completeResetSuccess(PluginStateResetEvent event) {
        if (event.expects(PLUGIN_ID)) {
            event.complete(PLUGIN_ID, PluginResetResult.reset(PLUGIN_ID, "Graphify 图谱与索引已清空"));
        }
    }

    private void completeResetFailure(PluginStateResetEvent event,
                                      Throwable error) {
        if (event.expects(PLUGIN_ID)) {
            event.complete(PLUGIN_ID, PluginResetResult.failed(PLUGIN_ID, error));
        }
        log.warn("插件 {} 状态重置失败", PLUGIN_ID, error);
    }

    @Override
    public void stop() {
        log.info("Graphify 插件正在停用");
        getMainApplicationContext().removeApplicationListener(this);
        if (indexService != null) {
            indexService.stop();
            indexService = null;
        }
        log.info("Graphify 插件已停用");
        super.stop();
    }
}
