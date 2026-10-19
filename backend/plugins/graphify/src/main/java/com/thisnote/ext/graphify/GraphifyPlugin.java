package com.thisnote.ext.graphify;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.ext.graphify.service.listener.AttachmentChangedEventListener;
import com.thisnote.ext.graphify.service.listener.CardChangedEventListener;
import com.thisnote.ext.graphify.service.listener.NoteChangedEventListener;
import com.thisnote.ext.graphify.service.listener.PluginStateResetListener;
import com.thisnote.ext.graphify.client.GraphifyAiClient;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

@Slf4j
public class GraphifyPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "graphify";

    private GraphifyIndexService indexService;
    private NoteChangedEventListener noteChangedEventListener;
    private CardChangedEventListener cardChangedEventListener;
    private AttachmentChangedEventListener attachmentChangedEventListener;
    private PluginStateResetListener stateResetListener;

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
        noteChangedEventListener = new NoteChangedEventListener(indexService);
        cardChangedEventListener = new CardChangedEventListener(indexService);
        attachmentChangedEventListener = new AttachmentChangedEventListener(indexService);
        stateResetListener = new PluginStateResetListener(indexService);
        getMainApplicationContext().addApplicationListener(noteChangedEventListener);
        getMainApplicationContext().addApplicationListener(cardChangedEventListener);
        getMainApplicationContext().addApplicationListener(attachmentChangedEventListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
        log.info("Graphify 插件已启动并注册笔记、卡片和附件监听器");
    }

    @Override
    public void stop() {
        log.info("Graphify 插件正在停用");
        if (noteChangedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteChangedEventListener);
            noteChangedEventListener = null;
        }
        if (cardChangedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(cardChangedEventListener);
            cardChangedEventListener = null;
        }
        if (attachmentChangedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(attachmentChangedEventListener);
            attachmentChangedEventListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        if (indexService != null) {
            indexService.stop();
            indexService = null;
        }
        log.info("Graphify 插件已停用");
        super.stop();
    }
}
