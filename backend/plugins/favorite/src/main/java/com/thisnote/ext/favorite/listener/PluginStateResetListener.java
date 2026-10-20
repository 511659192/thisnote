package com.thisnote.ext.favorite.listener;

import com.thisnote.ext.favorite.FavoritePlugin;
import com.thisnote.ext.favorite.service.FavoriteService;
import com.thisnote.plugin.api.event.PluginResetResult;
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
        PluginStateResetEvent reset = event.getPayload();
        try {
            favoriteService.clear();
            if (reset.expects(FavoritePlugin.PLUGIN_ID)) {
                reset.complete(FavoritePlugin.PLUGIN_ID,
                        PluginResetResult.reset(FavoritePlugin.PLUGIN_ID, "收藏已清空"));
            }
        } catch (Exception e) {
            if (reset.expects(FavoritePlugin.PLUGIN_ID)) {
                reset.complete(FavoritePlugin.PLUGIN_ID,
                        PluginResetResult.failed(FavoritePlugin.PLUGIN_ID, e));
            }
            log.error("插件 {} 状态重置失败", FavoritePlugin.PLUGIN_ID, e);
        }
    }
}
