package com.thisnode.ext.memorycycle;

import com.thisnode.ext.memorycycle.app.MemoryCycleStateListener;
import com.thisnode.ext.memorycycle.app.NoteDeletionListener;
import com.thisnode.ext.memorycycle.app.SchedulingListener;
import com.thisnode.ext.memorycycle.app.SrsService;

import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 记忆周期插件：thisnote 的**卡片插件**参考实现（参考 Anki 的 SM-2 间隔重复）。
 *
 * <p>它的状态全在本插件自己的文件里：排期写在 {@code plugins/memory-cycle/scheduling.meta}，
 * 卡片开关写在 {@code plugins/memory-cycle/enabled-cards.properties}。它**不依赖核心插件**，也不读笔记内容 ——
 * 核心插件缺位时它照样能独立启动。开启某张卡片会立刻建一条到期记录，于是马上可复习；
 * 关闭卡片的记忆周期时会删除对应排期。
 *
 * <p>id 与版本来自 {@code plugin.properties}（由 {@code descriptor()} 从 PF4J 描述符取），不在这里硬编码。
 */
public class MemoryCyclePlugin extends SpringBootPlugin {

    /** 端点前缀；必须与 {@code plugin.properties} 里的 plugin.id 一致。 */
    public static final String PLUGIN_ID = "memory-cycle";

    /** 注册到主容器的排期清理器（停止时要摘掉，见 {@link #stop()}）。 */
    private SchedulingListener listener;
    private NoteDeletionListener noteDeletionListener;
    private MemoryCycleStateListener stateListener;

    public MemoryCyclePlugin(PluginWrapper wrapper) {
        // SBP 的构造器要的是 (PluginWrapper, IPluginConfigurer[])；本插件没有需要共享的 configurer。
        super(wrapper, new IPluginConfigurer[0]);
    }

    /**
     * 插件自己的 Spring 引导：扫描本包（控制器/服务）；业务状态由本插件管理，
     * 与平台之间仅通过必要的插件契约和事件协作。
     *
     * <p>这里**不再**导入 {@code NoteGateway}：本插件不读笔记数据，也就不依赖核心插件。
     */
    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, MemoryCycleStarter.class);
    }

    /**
     * 插件的服务来自**插件自己的容器**（不是主容器）：SBP 启动插件时才会 refresh 它，
     * 所以这里按需从插件上下文取，而不是在构造期注入。
     */
    private SrsService srs() {
        return getApplicationContext().getBean(SrsService.class);
    }

    /**
     * 【spike，验证完即删】对照组：把同一个 listener 分别注册到**插件自己的容器**与**主容器**。
     *
     * <p>如果"事件只在本容器广播"成立，那么只有注册在主容器的那份会收到 notes 发的事件。
     */
    /**
     * 把排期清理器挂到**主容器**上：跨插件容器的事件不会传播，发布方（notes）也是往主容器发的。
     *
     * <p>此刻 {@code getApplicationContext()} 仍是 null（SBP 还没把它赋给插件），所以插件容器里的
     * bean 要用回调给的 {@code ctx} 取。
     */
    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap,
                                     org.springframework.context.support.GenericApplicationContext ctx) {
        SrsService srs = ctx.getBean(SrsService.class);
        srs.setMainEventPublisher(getMainApplicationContext());
        listener = new SchedulingListener(srs);
        noteDeletionListener = new NoteDeletionListener(srs);
        stateListener = new MemoryCycleStateListener(srs);
        getMainApplicationContext().addApplicationListener(listener);
        getMainApplicationContext().addApplicationListener(noteDeletionListener);
        getMainApplicationContext().addApplicationListener(stateListener);
    }

    /**
     * 插件停止时把 listener 从主容器摘掉：主容器比插件活得久，留着它既泄漏、也会让下次启用
     * 变成重复注册（同一条删除事件被清两次）。
     */
    @Override
    public void stop() {
        if (listener != null) {
            getMainApplicationContext().removeApplicationListener(listener);
            listener = null;
        }
        if (noteDeletionListener != null) {
            getMainApplicationContext().removeApplicationListener(noteDeletionListener);
            noteDeletionListener = null;
        }
        if (stateListener != null) {
            getMainApplicationContext().removeApplicationListener(stateListener);
            stateListener = null;
        }
        super.stop();
    }

}
