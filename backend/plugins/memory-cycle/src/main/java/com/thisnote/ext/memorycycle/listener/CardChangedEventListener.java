package com.thisnote.ext.memorycycle.listener;

import com.thisnote.ext.memorycycle.service.MemoryCycleService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class CardChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final MemoryCycleService memoryCycleService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        if (change.kind() != CardChangedEvent.Kind.DELETED) {
            return;
        }
        if (memoryCycleService.delete(change.cardId())) {
            log.info("卡片已删除，复习状态一并清除：{}", change.cardId());
        }
    }
}
