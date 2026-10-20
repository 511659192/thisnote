package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.NoteListQueryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class NoteListQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteListQueryEvent>> {

    private final NoteService noteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteListQueryEvent> event) {
        NoteListQueryEvent query = event.getPayload();
        try {
            query.result().complete(noteService.all());
        } catch (Exception e) {
            query.result().completeExceptionally(e);
        }
    }
}
