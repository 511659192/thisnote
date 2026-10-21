package com.thisnote.ext.aititle.listener;

import com.thisnote.plugin.api.event.NoteTitleAvailabilityFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
public class NoteTitleAvailabilityEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteTitleAvailabilityFutureEvent>> {

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteTitleAvailabilityFutureEvent> event) {
        event.getPayload().result().complete(true);
        log.debug("AI 标题能力可用性查询已响应");
    }
}
