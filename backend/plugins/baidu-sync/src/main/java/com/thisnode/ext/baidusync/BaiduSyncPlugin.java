package com.thisnode.ext.baidusync;

import com.thisnode.ext.baidusync.service.FileChangeEventListener;
import com.thisnode.ext.baidusync.service.BaiduSyncService;
import com.thisnode.plugin.api.util.FileHelper;
import com.thisnode.plugin.api.util.MainContainerListeners;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.springframework.context.support.GenericApplicationContext;
import org.pf4j.PluginWrapper;

public class BaiduSyncPlugin extends SpringBootPlugin {

    public static final String PLUGIN_ID = "baidu-sync";

    /** 本插件注册到主容器的全部跨插件监听器（停止时统一摘除）。 */
    private MainContainerListeners listeners;

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
        listeners = new MainContainerListeners(getMainApplicationContext());
        // 必须用插件容器里的 @Async 代理实例：网盘同步耗时，不能在事件发布方线程内执行。
        listeners.add(ctx.getBean(FileChangeEventListener.class));
        listeners.onStateReset("同步基线已清理", ctx.getBean(BaiduSyncService.class)::clearSyncState);
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
