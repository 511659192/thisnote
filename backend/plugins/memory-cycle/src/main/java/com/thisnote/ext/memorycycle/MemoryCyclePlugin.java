package com.thisnote.ext.memorycycle;

import com.thisnote.ext.memorycycle.service.MemoryCycleService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import com.thisnote.plugin.api.event.MemoryCycleStateChangedEvent;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import com.thisnote.plugin.api.event.PluginResetResult;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import com.thisnote.plugin.api.util.FileHelper;
import lombok.extern.slf4j.Slf4j;

import org.laxture.sbp.SpringBootPlugin;
import org.laxture.sbp.spring.boot.IPluginConfigurer;
import org.laxture.sbp.spring.boot.SpringBootstrap;
import org.pf4j.PluginWrapper;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

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
@Slf4j
public class MemoryCyclePlugin extends SpringBootPlugin implements GenericApplicationListener {

    /** 端点前缀；必须与 {@code plugin.properties} 里的 plugin.id 一致。 */
    public static final String PLUGIN_ID = "memory-cycle";

    private MemoryCycleService memoryCycleService;

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
        memoryCycleService = ctx.getBean(MemoryCycleService.class);
        memoryCycleService.setMainEventPublisher(getMainApplicationContext());
        getMainApplicationContext().addApplicationListener(this);
    }

    @Override
    public boolean supportsEventType(ResolvableType eventType) {
        return eventType.toClass() == PayloadApplicationEvent.class;
    }

    @Override
    public void onApplicationEvent(ApplicationEvent event) {
        if (!(event instanceof PayloadApplicationEvent<?> payloadEvent)) {
            return;
        }
        Object payload = payloadEvent.getPayload();
        if (payload instanceof CardChangedEvent change) {
            if (change.kind() == CardChangedEvent.Kind.DELETED && memoryCycleService.delete(change.cardId())) {
                log.info("卡片已删除，复习状态一并清除：{}", change.cardId());
            }
        } else if (payload instanceof NoteChangedEvent change) {
            if (change.kind() != NoteChangedEvent.Kind.DELETED) {
                return;
            }
            int removedCards = 0;
            for (String cardId : change.cardIds()) {
                if (memoryCycleService.delete(cardId)) {
                    removedCards++;
                }
            }
            log.info("笔记已删除，相关复习状态已清理：noteId={}, cards={}", change.noteId(), removedCards);
        } else if (payload instanceof MemoryCycleStateChangedEvent change) {
            if (!change.enabled() && memoryCycleService.delete(change.cardId())) {
                log.info("记忆周期已关闭，排期条目一并清除：{}", change.cardId());
            }
        } else if (payload instanceof PluginStateResetEvent resetEvent) {
            try {
                memoryCycleService.clearAll();
                if (resetEvent.expects(PLUGIN_ID)) {
                    resetEvent.complete(PLUGIN_ID, PluginResetResult.reset(PLUGIN_ID, "记忆周期排期与复习历史已清空"));
                }
            } catch (RuntimeException e) {
                if (resetEvent.expects(PLUGIN_ID)) {
                    resetEvent.complete(PLUGIN_ID, PluginResetResult.failed(PLUGIN_ID, e));
                }
                log.warn("插件 {} 状态重置失败", PLUGIN_ID, e);
            }
        }
    }

    /**
     * 插件停止时把 listener 从主容器摘掉：主容器比插件活得久，留着它既泄漏、也会让下次启用
     * 变成重复注册（同一条删除事件被清两次）。
     */
    @Override
    public void stop() {
        getMainApplicationContext().removeApplicationListener(this);
        super.stop();
    }

}
