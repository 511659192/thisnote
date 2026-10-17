package com.thisnode.ext.favorite.service;

import com.thisnode.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 状态重置：清空收藏（注册在主容器上，由 state-reset 插件触发）。
 */
@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final FavoriteService favoriteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        favoriteService.clear();
        log.info("收到状态重置事件（来自 {}）：收藏已清空", event.getPayload().sourcePluginId());
    }
}
