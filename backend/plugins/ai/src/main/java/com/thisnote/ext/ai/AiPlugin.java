package com.thisnote.ext.ai;

import com.thisnote.ext.ai.client.AiProviderClient;
import com.thisnote.ext.ai.listener.AiProfilesEventListener;
import com.thisnote.ext.ai.listener.AiCompletionEventListener;
import com.thisnote.ext.ai.listener.AiRuntimeEventListener;
import com.thisnote.ext.ai.service.AiCompletionExecutor;
import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

public class AiPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "ai";

    private AiRuntimeEventListener aiRuntimeEventListener;
    private AiProfilesEventListener aiProfilesEventListener;
    private AiCompletionEventListener aiCompletionEventListener;

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
        aiRuntimeEventListener = new AiRuntimeEventListener(ctx.getBean(AiSettingsService.class));
        aiProfilesEventListener = new AiProfilesEventListener(ctx.getBean(AiSettingsService.class));
        aiCompletionEventListener = new AiCompletionEventListener(
                ctx.getBean(AiSettingsService.class),
                ctx.getBean(AiProviderClient.class),
                ctx.getBean(AiCompletionExecutor.class)
        );
        getMainApplicationContext().addApplicationListener(aiRuntimeEventListener);
        getMainApplicationContext().addApplicationListener(aiProfilesEventListener);
        getMainApplicationContext().addApplicationListener(aiCompletionEventListener);
    }

    @Override
    public void stop() {
        if (aiRuntimeEventListener != null) {
            getMainApplicationContext().removeApplicationListener(aiRuntimeEventListener);
            aiRuntimeEventListener = null;
        }
        if (aiProfilesEventListener != null) {
            getMainApplicationContext().removeApplicationListener(aiProfilesEventListener);
            aiProfilesEventListener = null;
        }
        if (aiCompletionEventListener != null) {
            getMainApplicationContext().removeApplicationListener(aiCompletionEventListener);
            aiCompletionEventListener = null;
        }
        super.stop();
    }
}
