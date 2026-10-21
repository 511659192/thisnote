package com.thisnote.ext.aititle;

import com.thisnote.ext.aititle.client.TitleAiClient;
import com.thisnote.ext.aititle.listener.NoteTitleAvailabilityEventListener;
import com.thisnote.ext.aititle.listener.NoteTitleSuggestionEventListener;
import com.thisnote.ext.aititle.service.TitleSettingsService;
import com.thisnote.ext.aititle.service.TitleSuggestionService;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

@Slf4j
public class AiTitlePlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "ai-title";

    private NoteTitleAvailabilityEventListener availabilityListener;
    private NoteTitleSuggestionEventListener suggestionListener;

    public AiTitlePlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, AiTitleStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        TitleSettingsService settingsService = ctx.getBean(TitleSettingsService.class);
        TitleSuggestionService suggestionService = ctx.getBean(TitleSuggestionService.class);
        settingsService.setMainEventPublisher(getMainApplicationContext());
        suggestionService.setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(TitleAiClient.class).setMainEventPublisher(getMainApplicationContext());

        availabilityListener = new NoteTitleAvailabilityEventListener();
        suggestionListener = new NoteTitleSuggestionEventListener(suggestionService);
        getMainApplicationContext().addApplicationListener(availabilityListener);
        getMainApplicationContext().addApplicationListener(suggestionListener);
        log.info("AI 标题插件已启动并注册标题能力监听器");
    }

    @Override
    public void stop() {
        log.info("AI 标题插件正在停止");
        if (availabilityListener != null) {
            getMainApplicationContext().removeApplicationListener(availabilityListener);
            availabilityListener = null;
        }
        if (suggestionListener != null) {
            getMainApplicationContext().removeApplicationListener(suggestionListener);
            suggestionListener = null;
        }
        log.info("AI 标题插件已停止");
        super.stop();
    }
}
