package com.thisnote.ext.ai;

import com.thisnote.ext.ai.service.AiTagSuggestionService;
import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.event.AiRuntimeEvent;
import com.thisnote.plugin.api.event.NoteTagSuggestionEvent;
import com.thisnote.plugin.api.util.FileHelper;
import com.thisnote.plugin.api.util.MainContainerListeners;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

public class AiPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "ai";

    private MainContainerListeners listeners;

    public AiPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, AiStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        listeners = new MainContainerListeners(getMainApplicationContext());
        AiSettingsService settingsService = ctx.getBean(AiSettingsService.class);
        listeners.onPayload(AiRuntimeEvent.class, event -> {
            try {
                event.runtime().complete(settingsService.runtime());
            } catch (RuntimeException e) {
                event.runtime().complete(new AiRuntimeEvent.Runtime("", "", ""));
            }
        });
        listeners.onPayload(NoteTagSuggestionEvent.class, ctx.getBean(AiTagSuggestionService.class)::suggest);
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
