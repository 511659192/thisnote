package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.CardChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class CardAttachmentCleanupListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        if (change.kind() == CardChangedEvent.Kind.DELETED) {
            attachmentService.deleteIfUnreferenced(change.removedAttachmentFileNames());
        }
    }
}
