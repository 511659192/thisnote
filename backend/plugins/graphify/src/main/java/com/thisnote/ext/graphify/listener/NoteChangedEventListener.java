package com.thisnote.ext.graphify.listener;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        indexService.onNoteChanged(event.getPayload());
    }
}
