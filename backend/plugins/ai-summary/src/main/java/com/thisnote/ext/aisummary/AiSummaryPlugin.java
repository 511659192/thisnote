package com.thisnote.ext.aisummary;

import com.thisnote.ext.aisummary.client.SummaryAiClient;
import com.thisnote.ext.aisummary.listener.GraphifyIndexCompletedEventListener;
import com.thisnote.ext.aisummary.listener.NoteChangedEventListener;
import com.thisnote.ext.aisummary.listener.PluginStateResetListener;
import com.thisnote.ext.aisummary.service.AiSummaryService;
import com.thisnote.ext.aisummary.service.AiSummarySettingsService;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.support.GenericApplicationContext;

@Slf4j
public class AiSummaryPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "ai-summary";

    private AiSummaryService summaryService;
    private NoteChangedEventListener noteChangedEventListener;
    private GraphifyIndexCompletedEventListener graphifyIndexCompletedEventListener;
    private PluginStateResetListener stateResetListener;

    public AiSummaryPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, AiSummaryStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        summaryService = ctx.getBean(AiSummaryService.class);
        summaryService.setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(SummaryAiClient.class).setMainEventPublisher(getMainApplicationContext());
        ctx.getBean(AiSummarySettingsService.class).setMainEventPublisher(getMainApplicationContext());

        noteChangedEventListener = new NoteChangedEventListener(summaryService);
        graphifyIndexCompletedEventListener = new GraphifyIndexCompletedEventListener(summaryService);
        stateResetListener = new PluginStateResetListener(summaryService);
        int invalidated = summaryService.invalidateAll();

        getMainApplicationContext().addApplicationListener(noteChangedEventListener);
        getMainApplicationContext().addApplicationListener(graphifyIndexCompletedEventListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
        log.info("AI 总结插件已启动并注册笔记、图谱和重置监听器：invalidated={}", invalidated);
    }

    @Override
    public void stop() {
        log.info("AI 总结插件正在停用");
        if (noteChangedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteChangedEventListener);
            noteChangedEventListener = null;
        }
        if (graphifyIndexCompletedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(graphifyIndexCompletedEventListener);
            graphifyIndexCompletedEventListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        if (summaryService != null) {
            summaryService.close();
            summaryService = null;
        }
        log.info("AI 总结插件已停用");
        super.stop();
    }
}
