package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.model.request.CreateCardRequest;
import com.thisnote.ext.notes.service.CardService;
import com.thisnote.ext.notes.service.NoteService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.util.List;

@RequiredArgsConstructor
@Slf4j
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
        try {
            log.debug("处理卡片创建申请：title={}, bookId={}, sourceFileName={}",
                    request.noteTitle(), request.bookId(), request.card().sourceFileName());
            var note = noteService.create(request.noteTitle(), request.bookId(), List.of());
            // 申请方可以在 meta 里给出初始宽高（附件导入按图片尺寸算）；缺失时用默认 720×260。
            var meta = request.card().meta();
            Double width = meta == null || meta.w() <= 0 ? null : meta.w();
            Double height = meta == null || meta.h() <= 0 ? null : meta.h();
            var created = cardService.create(note.noteId(), new CreateCardRequest(null, 0d, 0d, width, height, request.card().content(), null), request.card().sourceFileName());
            log.info("卡片创建申请已处理：noteId={}, cardId={}, title={}, sourceFileName={}",
                    note.noteId(), created.meta() == null ? null : created.meta().cardId(),
                    request.noteTitle(), request.card().sourceFileName());
        } catch (RuntimeException e) {
            log.error("处理卡片创建申请失败：title={}, bookId={}, sourceFileName={}",
                    request.noteTitle(), request.bookId(), request.card().sourceFileName(), e);
            throw e;
        }
    }
}
