package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.CardService;
import com.thisnote.plugin.api.event.CardQueryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class CardQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardQueryEvent>> {

    private final CardService cardService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardQueryEvent> event) {
        try {
            event.getPayload().result().complete(cardService.listUnowned());
        } catch (Exception e) {
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
