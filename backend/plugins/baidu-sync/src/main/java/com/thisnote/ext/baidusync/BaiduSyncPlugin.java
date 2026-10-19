package com.thisnote.ext.baidusync;

import com.thisnote.ext.baidusync.service.FileChangeEventListener;
import com.thisnote.ext.baidusync.service.BaiduSyncService;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginManager;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;
import org.pf4j.PluginWrapper;

@Slf4j
public class BaiduSyncPlugin extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "baidu-sync";

    private BaiduSyncService baiduSyncService;
    private FileChangeEventListener fileChangeListener;

    public BaiduSyncPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, BaiduSyncStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        baiduSyncService = ctx.getBean(BaiduSyncService.class);
        baiduSyncService.initialize(
                getMainApplicationContext().getBean(PluginManager.class));
        // 必须用插件容器里的 @Async 代理实例：网盘同步耗时，不能在事件发布方线程内执行。
        fileChangeListener = ctx.getBean(FileChangeEventListener.class);
        getMainApplicationContext().addApplicationListener(fileChangeListener);
        getMainApplicationContext().addApplicationListener(this);
    }

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return eventType.toClass() == PayloadApplicationEvent.class;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!(event instanceof PayloadApplicationEvent<?> payloadEvent)
                || !(payloadEvent.getPayload() instanceof PluginStateResetEvent resetEvent)) {
            return;
        }
        try {
            baiduSyncService.clearSyncState();
            if (resetEvent.expects(PLUGIN_ID)) {
                resetEvent.complete(PLUGIN_ID, PluginResetResult.reset(PLUGIN_ID, "同步基线已清理"));
            }
        } catch (RuntimeException e) {
            if (resetEvent.expects(PLUGIN_ID)) {
                resetEvent.complete(PLUGIN_ID, PluginResetResult.failed(PLUGIN_ID, e));
            }
            log.warn("插件 {} 状态重置失败", PLUGIN_ID, e);
        }
    }

    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        if (fileChangeListener != null) {
            getMainApplicationContext().removeApplicationListener(fileChangeListener);
            fileChangeListener = null;
        }
        super.stop();
    }
}
