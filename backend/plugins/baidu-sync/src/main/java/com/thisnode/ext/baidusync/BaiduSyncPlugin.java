package com.thisnode.ext.baidusync;

import com.thisnode.ext.baidusync.service.FileChangeEventListener;
import com.thisnode.ext.baidusync.service.BaiduSyncService;
import com.thisnode.plugin.api.util.FileHelper;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.springframework.context.support.GenericApplicationContext;
import org.pf4j.PluginWrapper;

public class BaiduSyncPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "baidu-sync";

    private FileChangeEventListener changeEventListener;

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
        FileHelper.registerSpringBeans(ctx, getMainApplicationContext(), PLUGIN_ID);
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap, GenericApplicationContext ctx) {
        changeEventListener = ctx.getBean(FileChangeEventListener.class);
        getMainApplicationContext().addApplicationListener(changeEventListener);
    }

    @Override
    public void stop() {
        if (changeEventListener != null) {
            getMainApplicationContext().removeApplicationListener(changeEventListener);
            changeEventListener = null;
        }
        super.stop();
    }
}
