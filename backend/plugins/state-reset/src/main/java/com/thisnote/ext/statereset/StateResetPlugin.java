package com.thisnote.ext.statereset;

import com.thisnote.ext.statereset.service.StateResetService;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

/**
 * 状态重置插件：活动插件走 {@code PluginStateResetEvent} 自身清理，停用插件按其静态清单清理。
 */
public class StateResetPlugin extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "state-reset";

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
        getMainApplicationContext().addApplicationListener(this);
    }

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return eventType.toClass() == PayloadApplicationEvent.class;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (event instanceof PayloadApplicationEvent<?> payloadEvent
                && payloadEvent.getPayload() instanceof PluginStateResetEvent resetEvent
                && resetEvent.expects(PLUGIN_ID)) {
            resetEvent.complete(PLUGIN_ID, PluginResetResult.skipped(PLUGIN_ID, "state-reset 无自有状态"));
        }
    }

    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        super.stop();
    }
}
