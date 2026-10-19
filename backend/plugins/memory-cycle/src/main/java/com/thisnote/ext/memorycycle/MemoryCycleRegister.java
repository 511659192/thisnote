package com.thisnote.ext.memorycycle;

import com.thisnote.plugin.api.lifecycle.PluginRegister;
import org.pf4j.Extension;

/**
 * 插件向平台贡献的运行时生命周期回调。
 *
 * <p>本扩展不需要注入插件服务，因此只需 {@code @Extension}。
 */
@Extension
public class MemoryCycleRegister implements PluginRegister {
}
