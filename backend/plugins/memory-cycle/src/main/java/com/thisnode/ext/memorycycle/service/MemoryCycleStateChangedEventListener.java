package com.thisnode.ext.memorycycle.service;

import com.thisnode.plugin.api.MemoryCycleStateChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MemoryCycleStateChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<MemoryCycleStateChangedEvent>> {

    private final MemoryCycleService srs;

    public MemoryCycleStateChangedEventListener(MemoryCycleService srs) {
        this.srs = srs;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<MemoryCycleStateChangedEvent> event) {
        MemoryCycleStateChangedEvent change = event.getPayload();
        if (!change.enabled() && srs.delete(change.cardId())) {
            log.info("记忆周期已关闭，排期条目一并清除：{}", change.cardId());
        }
    }
}
