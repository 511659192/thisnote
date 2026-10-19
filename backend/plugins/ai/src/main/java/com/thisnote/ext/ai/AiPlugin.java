package com.thisnote.ext.ai;

import com.thisnote.ext.ai.service.AiTagSuggestionService;
import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.event.AiRuntimeEvent;
import com.thisnote.plugin.api.event.NoteTagSuggestionEvent;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import com.thisnote.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

public class AiPlugin extends SpringBootPlugin implements GenericApplicationListener {

    public static final String PLUGIN_ID = "ai";

    private AiSettingsService settingsService;
    private AiTagSuggestionService aiTagSuggestionService;

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
        settingsService = ctx.getBean(AiSettingsService.class);
        aiTagSuggestionService = ctx.getBean(AiTagSuggestionService.class);
        getMainApplicationContext().addApplicationListener(this);
    }

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return eventType.toClass() == PayloadApplicationEvent.class;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!(event instanceof PayloadApplicationEvent<?> payloadEvent)) {
            return;
        }
        Object payload = payloadEvent.getPayload();
        if (payload instanceof AiRuntimeEvent runtimeEvent) {
            try {
                runtimeEvent.runtime().complete(settingsService.runtime());
            } catch (RuntimeException e) {
                runtimeEvent.runtime().complete(new AiRuntimeEvent.Runtime("", "", ""));
            }
        } else if (payload instanceof NoteTagSuggestionEvent suggestionEvent) {
            aiTagSuggestionService.suggest(suggestionEvent);
        } else if (payload instanceof PluginStateResetEvent resetEvent) {
            if (resetEvent.expects(PLUGIN_ID)) {
                resetEvent.complete(PLUGIN_ID, PluginResetResult.skipped(PLUGIN_ID, "AI 配置与凭据保留"));
            }
        }
    }

    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        super.stop();
    }
}
