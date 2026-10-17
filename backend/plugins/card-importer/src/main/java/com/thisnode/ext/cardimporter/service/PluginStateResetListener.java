package com.thisnode.ext.cardimporter.service;

import com.thisnode.plugin.api.event.PluginStateResetEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 状态重置：本插件没有持久化状态，只记一条日志。
 */
@Slf4j
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        log.info("收到状态重置事件（来自 {}）：card-importer 无自有状态", event.getPayload().sourcePluginId());
    }
}
