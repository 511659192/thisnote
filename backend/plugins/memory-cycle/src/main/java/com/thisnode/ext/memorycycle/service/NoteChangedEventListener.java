package com.thisnode.ext.memorycycle.service;

import com.thisnode.plugin.api.NoteChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private static final Logger log = LoggerFactory.getLogger(NoteChangedEventListener.class);

    private final MemoryCycleService srs;

    public NoteChangedEventListener(MemoryCycleService srs) {
        this.srs = srs;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        if (change.kind() != NoteChangedEvent.Kind.DELETED) {
            return;
        }
        int removedCards = 0;
        for (String cardId : change.cardIds()) {
            if (srs.delete(cardId)) {
                removedCards++;
            }
        }
        log.info("笔记已删除，相关复习状态已清理：noteId={}, cards={}", change.noteId(), removedCards);
    }
}
