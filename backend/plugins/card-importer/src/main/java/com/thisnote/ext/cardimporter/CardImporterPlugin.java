package com.thisnote.ext.cardimporter;

import com.thisnote.ext.cardimporter.service.CardImporterService;
import com.thisnote.plugin.api.util.MainContainerListeners;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

public class CardImporterPlugin
        extends SpringBootPlugin {

    public static final String PLUGIN_ID = "card-importer";

    /** 本插件注册到主容器的全部跨插件监听器（停止时统一摘除）。 */
    private MainContainerListeners listeners;

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
        listeners = new MainContainerListeners(getMainApplicationContext());
        listeners.onStateResetSkipped(PLUGIN_ID, "card-importer 无自有状态");
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
