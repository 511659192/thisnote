package com.thisnote.ext.favorite.listener;

import com.thisnote.ext.favorite.FavoritePlugin;
import com.thisnote.ext.favorite.service.FavoriteService;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final FavoriteService favoriteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            favoriteService.clear();
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", FavoritePlugin.PLUGIN_ID, e);
        }
    }
}
