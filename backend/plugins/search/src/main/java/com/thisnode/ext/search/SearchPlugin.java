package com.thisnode.ext.search;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginKind;
import com.thisnode.plugin.support.ThisnodePlugin;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 标题搜索插件：thisnode 的**服务插件**最小参考实现。
 *
 * <p>它没有任何端点、没有状态文件 —— 能力全部在前端：列表头部的搜索框经列表筛选插槽
 * 过滤笔记列表。jar 在这里存在的意义只有两个：让平台「认识」这个插件（列表/启停/健康），
 * 以及承载前端产物的版本管理（frontend-dist/ 由 antrun 随打包拷到 plugins-dist/frontend/）。
 */
public class SearchPlugin extends ThisnodePlugin {

    /** 与 {@code plugin.properties} 里的 plugin.id 一致；前端注册（路由/插槽）也用它。 */
    public static final String PLUGIN_ID = "search";

    public SearchPlugin(PluginWrapper wrapper) {
        super(wrapper, PluginKind.SERVICE, "模糊搜索",
                "顶栏模糊查询（标题/摘要，可跳转）+ 列表头部按标题过滤；纯前端实现，关闭即入口消失，无任何数据");
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        // 没有控制器也没有数据：只起一个空的插件容器（SBP 需要一个 primarySource）。
        return new SpringBootstrap(this, SearchStarter.class);
    }

    @Override
    public void onGlobalStateChanged(boolean enabled) {
        // 前端插槽随启停装卸（unmount 注销），这里没有后台动作。
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("纯前端能力，无后端状态");
    }
}
