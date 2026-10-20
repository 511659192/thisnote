package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.model.NotesMapper;
import com.thisnote.ext.notes.repository.NoteRepository;
import com.thisnote.plugin.api.event.NoteListFutureEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class NoteListQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteListFutureEvent>> {

    private final NoteRepository noteRepository;
    private final NotesMapper notesMapper;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteListFutureEvent> event) {
        NoteListFutureEvent query = event.getPayload();
        try {
            query.result().complete(noteRepository.getAll().stream().map(notesMapper::toNoteDTO).toList());
        } catch (Exception e) {
            query.result().completeExceptionally(e);
        }
    }
}
