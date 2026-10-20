package com.thisnote.ext.aisummary.listener;

import com.thisnote.ext.aisummary.service.AiSummaryService;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final AiSummaryService summaryService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            summaryService.resetAll();
        } catch (Exception e) {
            log.error("AI 总结插件状态重置失败", e);
        }
    }
}
