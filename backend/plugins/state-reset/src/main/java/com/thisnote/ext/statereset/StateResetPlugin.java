package com.thisnote.ext.statereset;

import com.thisnote.ext.statereset.service.StateResetService;
import com.thisnote.ext.statereset.listener.PluginStateResetListener;
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

    private PluginStateResetListener stateResetListener;

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
        stateResetListener = new PluginStateResetListener();
        getMainApplicationContext().addApplicationListener(stateResetListener);
    }

    @Override
    public void stop() {
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }
}
