package com.thisnote.ext.statereset;

import com.thisnote.ext.statereset.service.StateResetService;
import com.thisnote.plugin.api.util.MainContainerListeners;
import com.thisnote.plugin.api.util.PluginStateResetSupport;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 状态重置插件：活动插件走 {@code PluginStateResetEvent} 自身清理，停用插件按其静态清单清理。
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
        StateResetService stateResetService = ctx.getBean(StateResetService.class);
        stateResetService.setMainEventPublisher(getMainApplicationContext());
        stateResetService.setPluginManager(getMainApplicationContext().getBean(PluginManager.class));
        listeners = new MainContainerListeners(getMainApplicationContext());
        PluginStateResetSupport.registerSkipped(listeners, PLUGIN_ID, "state-reset 无自有状态");
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
