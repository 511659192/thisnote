package com.thisnote.ext.memorycycle;

import com.thisnote.ext.memorycycle.listener.CardChangedEventListener;
import com.thisnote.ext.memorycycle.listener.NoteChangedEventListener;
import com.thisnote.ext.memorycycle.listener.PluginStateResetListener;
import com.thisnote.ext.memorycycle.repository.CardScheduleRepository;
import com.thisnote.ext.memorycycle.repository.ScheduleHistoryRepository;
import com.thisnote.plugin.api.util.FileHelper;

import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;

/**
 * 记忆周期插件：thisnote 的**卡片插件**参考实现（参考 Anki 的 SM-2 间隔重复）。
 *
 * <p>它的状态全在本插件自己的 {@code plugins/memory-cycle/.scheduling.json} 里；有排期即表示已开启。
 * 它**不依赖核心插件**，也不读笔记内容 ——
 * 核心插件缺位时它照样能独立启动。开启某张卡片会立刻建一条到期记录，于是马上可复习；
 * 关闭卡片的记忆周期时会删除对应排期。
 *
 * <p>id 与版本来自 {@code plugin.properties}（由 {@code descriptor()} 从 PF4J 描述符取），不在这里硬编码。
 */
public class MemoryCyclePlugin extends SpringBootPlugin {

    /** 端点前缀；必须与 {@code plugin.properties} 里的 plugin.id 一致。 */
    public static final String PLUGIN_ID = "memory-cycle";

    private CardChangedEventListener cardChangedEventListener;
    private NoteChangedEventListener noteChangedEventListener;
    private PluginStateResetListener stateResetListener;

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

    @Override
    public void onPluginBootstrap(SpringBootstrap bootstrap,
                                  org.springframework.context.support.GenericApplicationContext ctx) {
        super.onPluginBootstrap(bootstrap, ctx);
        ctx.registerBean(FileHelper.class, () -> new FileHelper(getMainApplicationContext(), PLUGIN_ID));
    }

    /**
     * 把排期清理器挂到**主容器**上：跨插件容器的事件不会传播，发布方（notes）也是往主容器发的。
     *
     * <p>此刻 {@code getApplicationContext()} 仍是 null（SBP 还没把它赋给插件），所以插件容器里的
     * bean 要用回调给的 {@code ctx} 取。
     */
    @Override
    public void afterPluginBootstrap(SpringBootstrap bootstrap,
                                     org.springframework.context.support.GenericApplicationContext ctx) {
        CardScheduleRepository scheduleRepository = ctx.getBean(CardScheduleRepository.class);
        ScheduleHistoryRepository historyRepository = ctx.getBean(ScheduleHistoryRepository.class);
        cardChangedEventListener = new CardChangedEventListener(scheduleRepository, historyRepository);
        noteChangedEventListener = new NoteChangedEventListener(scheduleRepository, historyRepository);
        stateResetListener = new PluginStateResetListener(scheduleRepository, historyRepository);
        getMainApplicationContext().addApplicationListener(cardChangedEventListener);
        getMainApplicationContext().addApplicationListener(noteChangedEventListener);
        getMainApplicationContext().addApplicationListener(stateResetListener);
    }

    /**
     * 插件停止时把 listener 从主容器摘掉：主容器比插件活得久，留着它既泄漏、也会让下次启用
     * 变成重复注册（同一条删除事件被清两次）。
     */
    @Override
    public void stop() {
        if (cardChangedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(cardChangedEventListener);
            cardChangedEventListener = null;
        }
        if (noteChangedEventListener != null) {
            getMainApplicationContext().removeApplicationListener(noteChangedEventListener);
            noteChangedEventListener = null;
        }
        if (stateResetListener != null) {
            getMainApplicationContext().removeApplicationListener(stateResetListener);
            stateResetListener = null;
        }
        super.stop();
    }

}
