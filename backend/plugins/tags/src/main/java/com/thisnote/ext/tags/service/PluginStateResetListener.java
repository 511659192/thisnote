package com.thisnote.ext.tags.service;

import com.thisnote.ext.tags.TagsPlugin;
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

    private final TagsService tagsService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        PluginStateResetEvent reset = event.getPayload();
        try {
            tagsService.clear();
            if (reset.expects(TagsPlugin.PLUGIN_ID)) {
                reset.complete(TagsPlugin.PLUGIN_ID,
                        PluginResetResult.reset(TagsPlugin.PLUGIN_ID, "标签已清空"));
            }
        } catch (RuntimeException e) {
            if (reset.expects(TagsPlugin.PLUGIN_ID)) {
                reset.complete(TagsPlugin.PLUGIN_ID,
                        PluginResetResult.failed(TagsPlugin.PLUGIN_ID, e));
            }
            log.warn("插件 {} 状态重置失败", TagsPlugin.PLUGIN_ID, e);
        }
    }
}
