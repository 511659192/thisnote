package com.thisnode.ext.memorycycle.app;

import com.thisnode.plugin.api.CardChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 卡片被删除时，把它的排期条目一起删掉 —— 本插件唯一需要「知道笔记那边发生了什么」的地方。
 *
 * <p>实例注册在**主容器**上（跨插件容器的事件不会传播），由 {@code MemoryCyclePlugin} 在
 * {@code afterPluginBootstrap} 里挂上。
 */
public class CardChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private static final Logger log = LoggerFactory.getLogger(CardChangedEventListener.class);

    private final SrsService srs;

    public CardChangedEventListener(SrsService srs) {
        this.srs = srs;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent payload = event.getPayload();
        if (payload.kind() != CardChangedEvent.Kind.DELETED) {
            return;
        }
        if (srs.forgetDeletedCard(payload.cardId())) {
            log.info("卡片已删除，复习状态一并清除：{}", payload.cardId());
        }
    }
}
