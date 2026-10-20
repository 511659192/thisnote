package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.model.NotesMapper;
import com.thisnote.ext.notes.repository.CardRepository;
import com.thisnote.plugin.api.event.CardFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class CardQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardFutureEvent>> {

    private final CardRepository cardRepository;
    private final NotesMapper notesMapper;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardFutureEvent> event) {
        CardFutureEvent query = event.getPayload();
        try {
            var cards = cardRepository.listUnowned().stream().map(notesMapper::toCardDTO).toList();
            query.result().complete(cards);
            if (!cards.isEmpty()) {
                log.debug("无主卡片查询完成：count={}", cards.size());
            }
        } catch (Exception e) {
            log.error("无主卡片查询失败", e);
            query.result().completeExceptionally(e);
        }
    }
}
