package com.thisnote.ext.notes.service.listener;

import com.thisnote.ext.notes.exception.NotFoundException;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.NoteContentQueryEvent;
import com.thisnote.plugin.api.notes.dto.NoteContentDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class NoteContentQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteContentQueryEvent>> {

    private final NoteService noteService;
    private final CardService cardService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteContentQueryEvent> event) {
        NoteContentQueryEvent query = event.getPayload();
        try {
            query.result().complete(new NoteContentDTO(
                    noteService.get(query.noteId()),
                    cardService.cards(query.noteId(), null)));
        } catch (NotFoundException e) {
            query.result().complete(null);
        } catch (RuntimeException e) {
            query.result().completeExceptionally(e);
        }
    }
}
