package com.thisnode.ext.favorite;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginRegister;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;

/**
 * 插件向平台贡献的全部能力（唯一的 PF4J 扩展点）：显示名/说明/健康 + 可选的状态回调。
 *
 * <p>写成插件容器里的 bean（@Component）—— SBP 的扩展工厂优先从容器取 bean，
 * 于是扩展类也能注入插件自己的服务。
 */
@Extension
@Component
public class FavoriteRegister implements PluginRegister {

    @Override
    public String description() {
        return "给笔记加 ★ 收藏，并提供一处「收藏」列表；关闭插件即整体下线，数据保留";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("收藏状态存于插件数据，可随插件启停");
    }

    @Override
    public String title() {
        return "收藏";
    }
}
