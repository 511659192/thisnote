package com.thisnote.ext.memorycycle.listener;

import com.thisnote.ext.memorycycle.repository.CardScheduleRepository;
import com.thisnote.ext.memorycycle.repository.ScheduleHistoryRepository;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class CardChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final CardScheduleRepository scheduleRepository;
    private final ScheduleHistoryRepository historyRepository;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        if (change.kind() != CardChangedEvent.Kind.DELETED) {
            return;
        }
        boolean removed = scheduleRepository.delete(change.cardId());
        historyRepository.delete(change.cardId());
        if (removed) {
            log.info("卡片已删除，复习状态一并清除：{}", change.cardId());
        }
    }
}
