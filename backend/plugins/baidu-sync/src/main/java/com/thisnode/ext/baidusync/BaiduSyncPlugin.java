package com.thisnode.ext.baidusync;

import com.thisnode.ext.baidusync.service.FileChangeEventListener;
import com.thisnode.ext.baidusync.service.BaiduSyncService;
import com.thisnode.ext.baidusync.service.PluginStateResetListener;
import com.thisnode.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.springframework.context.support.GenericApplicationContext;
import org.pf4j.PluginWrapper;

public class BaiduSyncPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "baidu-sync";

    private FileChangeEventListener changeEventListener;
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
        changeEventListener = ctx.getBean(FileChangeEventListener.class);
        stateResetListener = new PluginStateResetListener(ctx.getBean(BaiduSyncService.class));
        getMainApplicationContext().addApplicationListener(changeEventListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
    }

    @Override
    public void stop() {
        if (changeEventListener != null) {
            getMainApplicationContext().removeApplicationListener(changeEventListener);
            changeEventListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }
}
