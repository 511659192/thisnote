package com.thisnote.ext.favorite;

import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

/**
 * 插件向平台贡献的运行时生命周期回调。
 *
 * <p>只需 {@code @Extension}：PF4J 的注解处理器把它写进 {@code META-INF/extensions.idx}，
 * SBP 的扩展工厂反射实例化（需要无参构造）。**要注入插件自己的服务时才加** {@code @Component}
 * （那时 SBP 改为从插件容器取 bean）—— 见同目录下注入了服务的那个实现。
 */
@Extension
public class FavoriteRegister implements PluginRegister {
}
