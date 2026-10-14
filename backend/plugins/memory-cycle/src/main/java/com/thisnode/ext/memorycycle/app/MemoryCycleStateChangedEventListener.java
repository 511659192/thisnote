package com.thisnode.ext.memorycycle.app;

import com.thisnode.plugin.api.MemoryCycleStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.stereotype.Component;

@Component
public class MemoryCycleStateChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<MemoryCycleStateChangedEvent>> {

    private static final Logger log = LoggerFactory.getLogger(MemoryCycleStateChangedEventListener.class);

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
