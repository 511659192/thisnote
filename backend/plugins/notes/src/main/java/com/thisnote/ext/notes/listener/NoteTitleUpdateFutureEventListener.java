package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.model.request.UpdateNoteRequest;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.NoteTitleUpdateFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class NoteTitleUpdateFutureEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteTitleUpdateFutureEvent>> {

    private final NoteService noteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteTitleUpdateFutureEvent> event) {
        NoteTitleUpdateFutureEvent request = event.getPayload();
        try {
            request.result().complete(noteService.update(
                    request.noteId(), new UpdateNoteRequest(request.noteId(), request.title())));
        } catch (Exception e) {
            log.error("应用 AI 标题失败：noteId={}, title={}", request.noteId(), request.title(), e);
            request.result().completeExceptionally(e);
        }
    }
}
