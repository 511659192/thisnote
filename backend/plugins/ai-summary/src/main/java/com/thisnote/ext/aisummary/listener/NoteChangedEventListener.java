package com.thisnote.ext.aisummary.listener;

import com.thisnote.ext.aisummary.service.AiSummaryService;
import com.thisnote.plugin.api.event.NoteChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class NoteChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteChangedEvent>> {

    private final AiSummaryService summaryService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteChangedEvent> event) {
        NoteChangedEvent change = event.getPayload();
        log.debug("AI 总结收到笔记变更：noteId={}, kind={}", change.noteId(), change.kind());
        try {
            if (change.kind() == NoteChangedEvent.Kind.DELETED) {
                summaryService.deleteNote(change.noteId());
                return;
            }
            summaryService.invalidateNote(change.noteId());
        } catch (Exception e) {
            log.error("AI 总结失效处理失败：noteId={}, kind={}", change.noteId(), change.kind(), e);
        }
    }
}
