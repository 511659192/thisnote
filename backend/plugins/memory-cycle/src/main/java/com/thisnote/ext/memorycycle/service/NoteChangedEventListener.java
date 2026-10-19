package com.thisnote.ext.memorycycle.service;

import com.thisnote.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private final MemoryCycleService memoryCycleService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        if (change.kind() != NoteChangedEvent.Kind.DELETED) {
            return;
        }
        int removedCards = 0;
        for (String cardId : change.cardIds()) {
            if (memoryCycleService.delete(cardId)) {
                removedCards++;
            }
        }
        log.info("笔记已删除，相关复习状态已清理：noteId={}, cards={}", change.noteId(), removedCards);
    }
}
