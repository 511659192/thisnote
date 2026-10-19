package com.thisnote.ext.cardimporter;

import com.thisnote.ext.cardimporter.service.CardImporterService;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

public class CardImporterPlugin
        extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "card-importer";

    public CardImporterPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, CardImporterStarter.class);
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap,
                                     GenericApplicationContext ctx) {
        ctx.getBean(CardImporterService.class).setMainEventPublisher(getMainApplicationContext());
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
            resetEvent.complete(PLUGIN_ID, PluginResetResult.skipped(PLUGIN_ID, "card-importer 无自有状态"));
        }
    }

    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        super.stop();
    }
}
