package com.thisnode.ext.fuzzysearch;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.support.ThisnodePlugin;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 模糊搜索插件：**服务插件**（纯界面侧能力）的参考实现。
 *
 * <p>它没有任何端点、没有状态文件 —— 能力全在界面侧：顶栏的模糊查询框（跨标题与摘要匹配，
 * 下拉结果可直接跳转）。jar 在这里存在的意义只有两个：让平台「认识」这个插件
 * （列表/启停/健康/版本），以及承载界面资源的交付（随 jar 一并放进交付目录）。
 */
public class FuzzySearchPlugin extends ThisnodePlugin {

    /** 与 {@code plugin.properties} 里的 plugin.id 一致；界面侧注册也用它。 */
    public static final String PLUGIN_ID = "fuzzy-search";

    public FuzzySearchPlugin(PluginWrapper wrapper) {
        super(wrapper, "模糊搜索",
                "顶栏跨标题/摘要的模糊查询，下拉结果直达笔记；纯界面侧实现，关闭即入口消失，无任何数据");
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        // 没有控制器也没有数据：只起一个空的插件容器（SBP 需要一个 primarySource）。
        return new SpringBootstrap(this, FuzzySearchStarter.class);
    }

    @Override
    public void onGlobalStateChanged(boolean enabled) {
        // 界面侧插槽随启停装卸（unmount 注销），这里没有后台动作。
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("纯界面侧能力，无后端状态");
    }
}
