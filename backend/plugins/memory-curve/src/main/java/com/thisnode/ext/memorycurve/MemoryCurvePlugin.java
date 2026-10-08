package com.thisnode.ext.memorycurve;

import com.thisnode.plugin.api.PluginHealth;
import com.thisnode.plugin.api.PluginKind;
import com.thisnode.plugin.support.ThisnodePlugin;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 记忆曲线插件：thisnote 的**卡片插件**参考实现（参考 Anki 的 SM-2 间隔重复）。
 *
 * <p>它的状态全在本插件自己的文件里：排期写在 {@code plugins/memory-curve/scheduling.meta}，
 * 开关用的是底座的 per-entity 状态（同样是文件）。它**不依赖核心插件**，也不读笔记内容 ——
 * 核心插件缺位时它照样能独立启动。开启某张卡片会立刻建一条到期记录，于是马上可复习；
 * 停用保留记录，历史不丢。
 *
 * <p>id 与版本来自 {@code plugin.properties}（{@link ThisnodePlugin} 负责取），不在这里硬编码。
 */
public class MemoryCurvePlugin extends ThisnodePlugin {

    /** 端点前缀与数据分区用的 id；必须与 {@code plugin.properties} 里的 plugin.id 一致。 */
    public static final String PLUGIN_ID = "memory-curve";

    /** 注册到主容器的排期清理器（停止时要摘掉，见 {@link #stop()}）。 */
    private SchedulingListener listener;

    public MemoryCurvePlugin(PluginWrapper wrapper) {
        super(wrapper, PluginKind.CARD, "记忆曲线",
                "参考 Anki 的 SM-2 间隔重复：按卡片安排复习，遗忘即回退，间隔随掌握程度增长");
    }

    /**
     * 插件自己的 Spring 引导：扫描本包（控制器/服务），并把平台的状态存储与数据源共享进插件容器
     * —— 插件与平台之间只有 plugin-api 这层契约，容器之间由 SBP 显式搭桥。
     *
     * <p>这里**不再**导入 {@code NoteGateway}：本插件不读笔记数据，也就不依赖核心插件。
     */
    @Override
    protected SpringBootstrap createSpringBootstrap() {
        return new SpringBootstrap(this, MemoryCurveStarter.class)
                .importBean(com.thisnode.plugin.api.PluginDataStore.class);
    }

    /**
     * 插件的服务来自**插件自己的容器**（不是主容器）：SBP 启动插件时才会 refresh 它，
     * 所以这里按需从插件上下文取，而不是在构造期注入。
     */
    private SrsService srs() {
        return getApplicationContext().getBean(SrsService.class);
    }

    @Override
    public void onEntityStateChanged(String entityType, String entityId, boolean enabled) {
        if (SrsService.ENTITY_TYPE_BLOCK.equals(entityType) && enabled) {
            srs().createIfAbsent(entityId);
        }
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
        if (listener == null) {
            listener = new SchedulingListener(ctx.getBean(SrsService.class));
        }
        getMainApplicationContext().addApplicationListener(listener);
    }

    /**
     * 插件停止时把 listener 从主容器摘掉：主容器比插件活得久，留着它既泄漏、也会让下次启用
     * 变成重复注册（同一条删除事件被清两次）。
     */
    @Override
    public void stop() {
        if (listener != null) {
            getMainApplicationContext().removeApplicationListener(listener);
        }
        super.stop();
    }

    @Override
    public PluginHealth health() {
        return PluginHealth.ok("开启记忆曲线的卡片会按间隔出现在复习页");
    }
}
