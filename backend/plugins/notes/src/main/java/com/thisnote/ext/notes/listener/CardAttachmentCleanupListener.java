package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class CardAttachmentCleanupListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        if (change.kind() == CardChangedEvent.Kind.DELETED) {
            try {
                attachmentService.deleteIfUnreferenced(change.removedAttachmentFileNames());
                log.debug("卡片附件清理检查完成：cardId={}, candidates={}",
                        change.cardId(), change.removedAttachmentFileNames().size());
            } catch (RuntimeException e) {
                log.error("卡片附件清理失败：cardId={}, candidates={}",
                        change.cardId(), change.removedAttachmentFileNames().size(), e);
                throw e;
            }
        }
    }
}
