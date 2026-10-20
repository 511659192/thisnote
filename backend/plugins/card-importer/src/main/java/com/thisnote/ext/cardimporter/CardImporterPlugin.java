package com.thisnote.ext.cardimporter;

import com.thisnote.ext.cardimporter.service.CardImporterService;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

public class CardImporterPlugin
        extends SpringBootPlugin {

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
    }
}
