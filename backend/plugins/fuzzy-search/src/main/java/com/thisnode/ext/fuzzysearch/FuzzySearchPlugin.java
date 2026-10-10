package com.thisnode.ext.fuzzysearch;

import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 模糊搜索插件：**服务插件**（纯界面侧能力）的参考实现。
 *
 * <p>它没有任何端点、没有状态文件 —— 能力全在界面侧：顶栏的模糊查询框（跨标题与摘要匹配，
 * 下拉结果可直接跳转）。jar 在这里存在的意义只有两个：让平台「认识」这个插件
 * （列表/启停/健康/版本），以及承载界面资源的交付（随 jar 一并放进交付目录）。
 */
public class FuzzySearchPlugin extends SpringBootPlugin {

    /** 与 {@code plugin.properties} 里的 plugin.id 一致；界面侧注册也用它。 */
    public static final String PLUGIN_ID = "fuzzy-search";

    public FuzzySearchPlugin(PluginWrapper wrapper) {
        // SBP 的构造器要的是 (PluginWrapper, IPluginConfigurer[])；本插件没有需要共享的
        // configurer（要共享的东西走 SpringBootstrap.importBean），所以传空数组。
        super(wrapper, new IPluginConfigurer[0]);
    }

    @Override
    protected SpringBootstrap createSpringBootstrap() {
        // 没有控制器也没有数据：只起一个空的插件容器（SBP 需要一个 primarySource）。
        return new SpringBootstrap(this, FuzzySearchStarter.class);
    }

}
