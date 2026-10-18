package com.thisnote.ext.favorite;

import com.thisnote.plugin.api.lifecycle.PluginHealth;
import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

/**
 * 插件向平台贡献的管理信息（唯一的 PF4J 扩展点）：显示名、说明与健康状态。
 *
 * <p>只需 {@code @Extension}：PF4J 的注解处理器把它写进 {@code META-INF/extensions.idx}，
 * SBP 的扩展工厂反射实例化（需要无参构造）。**要注入插件自己的服务时才加** {@code @Component}
 * （那时 SBP 改为从插件容器取 bean）—— 见同目录下注入了服务的那个实现。
 */
@Extension
public class FavoriteRegister implements PluginRegister {

    @Override
    public String description() {
        return "给笔记加 ★ 收藏，并提供一处「收藏」列表；关闭插件即整体下线，数据保留";
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("收藏状态存于本插件的独立文件");
    }

    @Override
    public String title() {
        return "收藏";
    }
}
