package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentAdoptEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class AttachmentAdoptEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentAdoptEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentAdoptEvent> event) {
        try {
            AttachmentAdoptEvent adopt = event.getPayload();
            adopt.result().complete(attachmentService.adopt(adopt.fileName()));
        } catch (Exception e) {
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
