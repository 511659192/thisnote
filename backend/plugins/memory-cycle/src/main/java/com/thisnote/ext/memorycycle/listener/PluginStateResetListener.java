package com.thisnote.ext.memorycycle.listener;

import com.thisnote.ext.memorycycle.MemoryCyclePlugin;
import com.thisnote.ext.memorycycle.repository.CardScheduleRepository;
import com.thisnote.ext.memorycycle.repository.ScheduleHistoryRepository;
import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class PluginStateResetListener
        implements ApplicationListener<PayloadApplicationEvent<PluginStateResetEvent>> {

    private final CardScheduleRepository scheduleRepository;
    private final ScheduleHistoryRepository historyRepository;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<PluginStateResetEvent> event) {
        try {
            scheduleRepository.deleteAll();
            historyRepository.deleteAll();
            log.info("记忆周期排期与复习历史已清空");
        } catch (Exception e) {
            log.error("插件 {} 状态重置失败", MemoryCyclePlugin.PLUGIN_ID, e);
        }
    }
}
