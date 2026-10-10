package com.thisnode.ext.search;

import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 标题搜索插件：thisnode 的**服务插件**最小参考实现。
 *
 * <p>它没有任何端点、没有状态文件 —— 能力全部在界面侧：列表头部的搜索框经列表筛选插槽
 * 过滤笔记列表。jar 在这里存在的意义只有两个：让平台「认识」这个插件（列表/启停/健康），
 * 以及承载界面资源的版本管理（随 jar 一并放进交付目录）。
 */
public class SearchPlugin extends SpringBootPlugin {

    /** 与 {@code plugin.properties} 里的 plugin.id 一致；界面侧注册（路由/插槽）也用它。 */
    public static final String PLUGIN_ID = "search";

    public SearchPlugin(PluginWrapper wrapper) {
        // SBP 的构造器要的是 (PluginWrapper, IPluginConfigurer[])；本插件没有需要共享的
        // configurer（要共享的东西走 SpringBootstrap.importBean），所以传空数组。
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        // 没有控制器也没有数据：只起一个空的插件容器（SBP 需要一个 primarySource）。
        return new SpringBootstrap(this, SearchStarter.class);
    }

}
