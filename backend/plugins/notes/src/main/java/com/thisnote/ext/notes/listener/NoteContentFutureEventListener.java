package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.exception.NotFoundException;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.NoteContentFutureEvent;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class NoteContentFutureEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteContentFutureEvent>> {

    private final NoteService noteService;
    private final CardService cardService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteContentFutureEvent> event) {
        NoteContentFutureEvent query = event.getPayload();
        try {
            query.result().complete(new NoteCardDTO(
                    noteService.get(query.noteId()),
                    cardService.cards(query.noteId(), null)));
        } catch (NotFoundException e) {
            query.result().complete(null);
        } catch (Exception e) {
            query.result().completeExceptionally(e);
        }
    }
}
