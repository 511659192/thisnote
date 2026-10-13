package com.thisnode.ext.memorycurve;

import com.thisnode.plugin.api.MemoryCurveStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class MemoryCurveStateListener
        implements ApplicationListener<PayloadApplicationEvent<MemoryCurveStateChangedEvent>> {

    private static final Logger log = LoggerFactory.getLogger(MemoryCurveStateListener.class);

    private final SrsService srs;

    public MemoryCurveStateListener(SrsService srs) {
        this.srs = srs;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<MemoryCurveStateChangedEvent> event) {
        MemoryCurveStateChangedEvent change = event.getPayload();
        if (!change.enabled() && srs.forget(change.cardId())) {
            log.info("记忆周期已关闭，排期条目一并清除：{}", change.cardId());
        }
    }
}
