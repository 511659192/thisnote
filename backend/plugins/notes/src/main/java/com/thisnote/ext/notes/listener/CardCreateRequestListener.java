package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.model.request.CreateCardRequest;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.util.List;

@RequiredArgsConstructor
public class CardCreateRequestListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final CardService cardService;
    private final NoteService noteService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        if (change.kind() != CardChangedEvent.Kind.REQUEST) {
            return;
        }
        var request = change.createRequest();
        var note = noteService.create(request.noteTitle(), request.bookId(), List.of());
        // 申请方可以在 meta 里给出初始宽高（附件导入按图片尺寸算）；缺失时用默认 720×260。
        var meta = request.card().meta();
        Double width = meta == null || meta.w() <= 0 ? null : meta.w();
        Double height = meta == null || meta.h() <= 0 ? null : meta.h();
        cardService.create(note.noteId(), new CreateCardRequest(null, 0d, 0d, width, height, request.card().content(), null), request.card().sourceFileName());
    }
}
