package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class CardChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        log.debug("Graphify 收到卡片变更：cardId={}, kind={}", change.cardId(), change.kind());
        if (change.kind() == CardChangedEvent.Kind.REQUEST) {
            return;
        }
        String noteId = indexService.noteIdForCard(change.cardId());
        if (noteId == null) {
            log.debug("Graphify 卡片尚未映射到笔记：cardId={}", change.cardId());
            indexService.queueUnresolvedCard(change.cardId());
        } else {
            indexService.queueNote(noteId);
        }
    }
}
