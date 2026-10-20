package com.thisnote.ext.baidusync;

import com.thisnote.ext.baidusync.listener.FileChangeEventListener;
import com.thisnote.ext.baidusync.service.BaiduSyncService;
import com.thisnote.ext.baidusync.listener.PluginStateResetListener;
import com.thisnote.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginManager;
import org.springframework.context.support.GenericApplicationContext;
import org.pf4j.PluginWrapper;

public class BaiduSyncPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "baidu-sync";

    private BaiduSyncService baiduSyncService;
    private FileChangeEventListener fileChangeListener;
    private PluginStateResetListener stateResetListener;

    public BaiduSyncPlugin(PluginWrapper wrapper) {
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, BaiduSyncStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        baiduSyncService = ctx.getBean(BaiduSyncService.class);
        baiduSyncService.initialize(
                getMainApplicationContext().getBean(PluginManager.class));
        // 必须用插件容器里的 @Async 代理实例：网盘同步耗时，不能在事件发布方线程内执行。
        fileChangeListener = ctx.getBean(FileChangeEventListener.class);
        stateResetListener = new PluginStateResetListener(baiduSyncService);
        getMainApplicationContext().addApplicationListener(fileChangeListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
    }

    @Override
    public void stop() {
        if (fileChangeListener != null) {
            getMainApplicationContext().removeApplicationListener(fileChangeListener);
            fileChangeListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }
}
