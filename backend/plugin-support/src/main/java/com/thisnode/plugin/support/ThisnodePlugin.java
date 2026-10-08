package com.thisnode.plugin.support;

import com.thisnode.plugin.api.Plugin;
import com.thisnode.plugin.api.PluginDescriptor;
import com.thisnode.plugin.api.PluginKind;
import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.pf4j.PluginWrapper;

/**
 * 本项目插件的基类：把「契约」与「PF4J/SBP」的接缝收在一处。
 *
 * <p>它替插件做三件事：
 * <ol>
 *   <li><b>id 与版本来自 PF4J 描述符</b>（{@code plugin.properties}，版本还由 Maven 过滤注入），
 *       插件里不再各写一份常量 —— 以前版本同时存在于 properties、Java 常量、测试断言三处，
 *       迟早出现"jar 里写的版本和实际构建对不上"。</li>
 *   <li>{@link #descriptor()} 只实现一次：插件只需要在构造器里说明自己的 kind/标题/说明。</li>
 *   <li>把 {@link IPluginConfigurer} 透传给 SBP（数据源等共享），保持插件的构造器一行。</li>
 * </ol>
 *
 * <p>为什么放在 {@code plugin-support}（随主应用装载）而不是 {@code plugin-api}：契约模块要保持
 * 「只有契约、不绑框架」，任何宿主都该能实现它；而这里明确绑 SBP。
 */
public abstract class ThisnodePlugin extends SpringBootPlugin implements Plugin {

    private final PluginKind kind;
    private final String title;
    private final String description;

    protected ThisnodePlugin(PluginWrapper wrapper, PluginKind kind, String title, String description,
                             IPluginConfigurer... configurers) {
        super(wrapper, configurers);
        this.kind = kind;
        this.title = title;
        this.description = description;
    }

    /** 插件 id：PF4J 描述符里的 {@code plugin.id}。 */
    protected String pluginId() {
        return getWrapper().getDescriptor().getPluginId();
    }

    /** 插件版本：PF4J 描述符里的 {@code plugin.version}（构建时由 Maven 注入）。 */
    protected String pluginVersion() {
        return getWrapper().getDescriptor().getVersion();
    }

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(pluginId(), title, description, kind, pluginVersion(), Plugin.API_VERSION);
    }
}
