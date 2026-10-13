package com.thisnode.ext.memorycycle.app;

import com.thisnode.plugin.api.MemoryCycleStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class MemoryCycleStateListener
        implements ApplicationListener<PayloadApplicationEvent<MemoryCycleStateChangedEvent>> {

    private static final Logger log = LoggerFactory.getLogger(MemoryCycleStateListener.class);

    private final SrsService srs;

    public MemoryCycleStateListener(SrsService srs) {
        this.srs = srs;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<MemoryCycleStateChangedEvent> event) {
        MemoryCycleStateChangedEvent change = event.getPayload();
        if (!change.enabled() && srs.forget(change.cardId())) {
            log.info("记忆周期已关闭，排期条目一并清除：{}", change.cardId());
        }
    }
}
