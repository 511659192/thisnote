package com.thisnode.ext.memorycurve;

import com.thisnode.plugin.api.NoteChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

public class NoteDeletionListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private static final Logger log = LoggerFactory.getLogger(NoteDeletionListener.class);

    private final SrsService srs;

    public NoteDeletionListener(SrsService srs) {
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
            if (srs.forgetDeletedCard(cardId)) {
                removedCards++;
            }
        }
        log.info("笔记已删除，相关复习状态已清理：noteId={}, cards={}", change.noteId(), removedCards);
    }
}
