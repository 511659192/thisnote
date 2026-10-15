package com.thisnode.ext.notes.service;

import com.thisnode.plugin.api.event.CardChangedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.stereotype.Component;

@Component
public class CardAttachmentCleanupListener
        implements ApplicationListener<PayloadApplicationEvent<CardChangedEvent>> {

    private final AttachmentService attachmentService;

    public CardAttachmentCleanupListener(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<CardChangedEvent> event) {
        CardChangedEvent change = event.getPayload();
        if (change.kind() == CardChangedEvent.Kind.DELETED) {
            attachmentService.deleteIfUnreferenced(change.removedAttachmentFileNames());
        }
    }
}
