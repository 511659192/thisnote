package com.thisnote.ext.memorycycle.listener;

import com.thisnote.ext.memorycycle.repository.CardScheduleRepository;
import com.thisnote.ext.memorycycle.repository.ScheduleHistoryRepository;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private final CardScheduleRepository scheduleRepository;
    private final ScheduleHistoryRepository historyRepository;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        if (change.kind() != NoteChangedEvent.Kind.DELETED) {
            return;
        }
        long removedCards = 0;
        for (String cardId : change.cardIds()) {
            boolean removed = scheduleRepository.delete(cardId);
            historyRepository.delete(cardId);
            if (removed) {
                removedCards++;
            }
        }
        log.info("笔记已删除，相关复习状态已清理：noteId={}, cards={}", change.noteId(), removedCards);
    }
}
