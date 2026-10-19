package com.thisnote.ext.graphify;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.ext.graphify.client.GraphifyAiClient;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.FileChangeEvent;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import com.thisnote.plugin.api.util.FileHelper;
import com.thisnote.plugin.api.util.MainContainerListeners;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

@Slf4j
public class GraphifyPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "graphify";

    private MainContainerListeners listeners;
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
        listeners = new MainContainerListeners(getMainApplicationContext());
        listeners.onPayload(NoteChangedEvent.class, indexService::onNoteChanged);
        listeners.onPayload(CardChangedEvent.class, indexService::onCardChanged);
        listeners.onPayload(FileChangeEvent.class, indexService::onFileChanged);
        listeners.onStateReset("Graphify 图谱与索引已清空", indexService::resetState);
        log.info("Graphify 插件已启动并注册笔记、卡片和附件监听器");
    }

    @Override
    public void stop() {
        log.info("Graphify 插件正在停用");
        if (listeners != null) {
            listeners.removeAll();
            listeners = null;
        }
        if (indexService != null) {
            indexService.stop();
            indexService = null;
        }
        log.info("Graphify 插件已停用");
        super.stop();
    }
}
