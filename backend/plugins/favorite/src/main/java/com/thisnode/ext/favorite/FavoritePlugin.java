package com.thisnode.ext.favorite;

import com.thisnode.plugin.api.PluginDataStore;
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
 * <p>它只依赖 plugin-api：没有自己的表，状态落在底座的 {@code PluginDataStore}（一个文件）。
 * id 与版本来自 {@code plugin.properties}，不在这里硬编码。
 */
public class FavoritePlugin extends SpringBootPlugin {

    /** 端点前缀与数据分区用的 id；必须与 {@code plugin.properties} 里的 plugin.id 一致。 */
    public static final String PLUGIN_ID = "favorite";
    public static final String ENTITY_TYPE_NOTE = "note";

    public FavoritePlugin(PluginWrapper wrapper) {
        // SBP 的构造器要的是 (PluginWrapper, IPluginConfigurer[])；本插件没有需要共享的
        // configurer（要共享的东西走 SpringBootstrap.importBean），所以传空数组。
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, FavoriteStarter.class)
                .importBean(PluginDataStore.class);
    }

}
