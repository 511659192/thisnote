package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.model.NotesMapper;
import com.thisnote.ext.notes.repository.CardRepository;
import com.thisnote.plugin.api.event.CardFutureEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class CardQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardFutureEvent>> {

    private final CardRepository cardRepository;
    private final NotesMapper notesMapper;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardFutureEvent> event) {
        try {
            event.getPayload().result().complete(
                    cardRepository.listUnowned().stream().map(notesMapper::toCardDTO).toList()
            );
        } catch (Exception e) {
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
