package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.model.NotesMapper;
import com.thisnote.ext.notes.repository.NoteRepository;
import com.thisnote.plugin.api.event.NoteListFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class NoteListFutureEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteListFutureEvent>> {

    private final NoteRepository noteRepository;
    private final NotesMapper notesMapper;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteListFutureEvent> event) {
        NoteListFutureEvent query = event.getPayload();
        try {
            var notes = noteRepository.getAll().stream().map(notesMapper::toNoteDTO).toList();
            query.result().complete(notes);
            log.debug("笔记列表查询完成：count={}", notes.size());
        } catch (Exception e) {
            log.error("笔记列表查询失败", e);
            query.result().completeExceptionally(e);
        }
    }
}
