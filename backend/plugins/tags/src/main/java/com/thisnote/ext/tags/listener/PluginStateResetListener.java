package com.thisnote.ext.tags.listener;

import com.thisnote.ext.tags.TagsPlugin;
import com.thisnote.ext.tags.repository.TagsRepository;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final TagsRepository tagsRepository;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            tagsRepository.deleteAll();
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", TagsPlugin.PLUGIN_ID, e);
        }
    }
}
