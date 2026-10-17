package com.thisnode.ext.notes.service.listener;

import com.thisnode.ext.notes.service.CardService;
import com.thisnode.plugin.api.event.CardQueryEvent;
import com.thisnode.plugin.api.notes.dto.CardDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class CardQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardQueryEvent>> {

    private final CardService cardService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardQueryEvent> event) {
        try {
            List<CardDTO> cards = cardService.listCards();
            event.getPayload().result().complete(cards);
        } catch (IOException | RuntimeException e) {
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
