package com.thisnode.ext.statereset;

import com.thisnode.ext.statereset.service.StateResetService;
import com.thisnode.plugin.api.util.MainContainerListeners;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 状态重置插件：只负责手动发出一次 {@code PluginStateResetEvent}。
 *
 * <p>清理什么、怎么重建由各插件自己决定；本插件不持有任何状态，也不认识具体插件。
 */
public class StateResetPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "state-reset";

    /** 本插件注册到主容器的全部跨插件监听器（停止时统一摘除）。 */
    private MainContainerListeners listeners;

    public StateResetPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, StateResetStarter.class);
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        ctx.getBean(StateResetService.class).setMainEventPublisher(getMainApplicationContext());
        listeners = new MainContainerListeners(getMainApplicationContext());
        listeners.onStateReset("state-reset 无自有状态", () -> { });
    }

    @Override
    public void stop() {
        if (listeners != null) {
            listeners.removeAll();
            listeners = null;
        }
        super.stop();
    }
}
