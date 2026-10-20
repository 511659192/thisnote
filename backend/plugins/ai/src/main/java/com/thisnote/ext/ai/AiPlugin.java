package com.thisnote.ext.ai;

import com.thisnote.ext.ai.service.AiTagSuggestionService;
import com.thisnote.ext.ai.listener.AiRuntimeEventListener;
import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.ext.ai.listener.NoteTagSuggestionEventListener;
import com.thisnote.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

public class AiPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "ai";

    private AiRuntimeEventListener aiRuntimeEventListener;
    private NoteTagSuggestionEventListener noteTagSuggestionEventListener;

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
        AiTagSuggestionService tagSuggestionService = ctx.getBean(AiTagSuggestionService.class);
        tagSuggestionService.setMainEventPublisher(getMainApplicationContext());
        aiRuntimeEventListener = new AiRuntimeEventListener(ctx.getBean(AiSettingsService.class));
        noteTagSuggestionEventListener = new NoteTagSuggestionEventListener(tagSuggestionService);
        getMainApplicationContext().addApplicationListener(aiRuntimeEventListener);
        getMainApplicationContext().addApplicationListener(noteTagSuggestionEventListener);
    }

    @Override
    public void stop() {
        if (aiRuntimeEventListener != null) {
            getMainApplicationContext().removeApplicationListener(aiRuntimeEventListener);
            aiRuntimeEventListener = null;
        }
        if (noteTagSuggestionEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteTagSuggestionEventListener);
            noteTagSuggestionEventListener = null;
        }
        super.stop();
    }
}
