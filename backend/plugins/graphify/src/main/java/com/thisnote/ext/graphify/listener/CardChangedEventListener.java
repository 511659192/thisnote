package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class CardChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        if (change.kind() == CardChangedEvent.Kind.REQUEST || !indexService.shouldObserveChanges()) {
            return;
        }
        String noteId = indexService.noteIdForCard(change.cardId());
        if (noteId == null) {
            indexService.queueUnresolvedCard(change.cardId());
        } else {
            indexService.queueNote(noteId);
        }
    }
}
