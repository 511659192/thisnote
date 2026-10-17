package com.thisnode.ext.memorycycle.service;

import com.thisnode.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 状态重置：清空全部记忆周期排期（注册在主容器上，由 state-reset 插件触发）。
 */
@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final MemoryCycleService memoryCycleService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        memoryCycleService.clearAll();
        log.info("收到状态重置事件（来自 {}）：记忆周期排期已清空", event.getPayload().sourcePluginId());
    }
}
