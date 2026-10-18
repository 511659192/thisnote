package com.thisnode.ext.favorite;

import com.thisnode.ext.favorite.service.FavoriteService;
import com.thisnode.plugin.api.event.NoteChangedEvent;
import com.thisnode.plugin.api.util.FileHelper;
import com.thisnode.plugin.api.util.MainContainerListeners;
import lombok.extern.slf4j.Slf4j;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 收藏插件：thisnote 的**实体插件**参考实现。
 *
 * <p>形态：{@code plugins/favorite.jar}，由 PF4J/SBP 用自己的 ClassLoader 装载、在自己的
 * Spring 子容器里运行，控制器再由 SBP 挂进主 MVC —— 所以外部路径仍是 {@code /api/plugins/favorite/**}。
 *
 * <p>收藏状态落在本插件自己的文件中，不依赖平台共享存储。
 * id 与版本来自 {@code plugin.properties}，不在这里硬编码。
 */
@Slf4j
public class FavoritePlugin extends SpringBootPlugin {

    /** 端点前缀；必须与 {@code plugin.properties} 里的 plugin.id 一致。 */
    public static final String PLUGIN_ID = "favorite";

    /** 本插件注册到主容器的全部跨插件监听器（停止时统一摘除）。 */
    private MainContainerListeners listeners;

    public FavoritePlugin(PluginWrapper wrapper) {
        // SBP 的构造器要的是 (PluginWrapper, IPluginConfigurer[])；本插件没有需要共享的 configurer。
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, FavoriteStarter.class);
    }

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap,
                                  org.springframework.context.support.GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap,
                                     org.springframework.context.support.GenericApplicationContext ctx) {
        FavoriteService favoriteService = ctx.getBean(FavoriteService.class);
        listeners = new MainContainerListeners(getMainApplicationContext());
        listeners.onPayload(NoteChangedEvent.class, change -> {
            if (change.kind() == NoteChangedEvent.Kind.DELETED && favoriteService.removeFavorite(change.noteId())) {
                log.info("笔记已删除，收藏记录一并清除：{}", change.noteId());
            }
        });
        listeners.onStateReset("收藏已清空", favoriteService::clear);
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
