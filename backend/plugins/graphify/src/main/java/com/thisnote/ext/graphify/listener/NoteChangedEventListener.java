package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        log.debug("Graphify 收到笔记变更：noteId={}, kind={}", change.noteId(), change.kind());
        if (change.kind() == NoteChangedEvent.Kind.TEXT_CHANGED) {
            return;
        }
        if (change.kind() == NoteChangedEvent.Kind.DELETED) {
            indexService.queueNoteDeletion(change.noteId());
        } else {
            indexService.queueNote(change.noteId());
        }
    }
}
