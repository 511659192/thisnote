package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.exception.NotFoundException;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.NoteContentFutureEvent;
import com.thisnote.plugin.api.notes.dto.NoteCardDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
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
            log.debug("笔记内容查询未命中：noteId={}", query.noteId());
            query.result().complete(null);
        } catch (Exception e) {
            log.error("笔记内容查询失败：noteId={}", query.noteId(), e);
            query.result().completeExceptionally(e);
        }
    }
}
