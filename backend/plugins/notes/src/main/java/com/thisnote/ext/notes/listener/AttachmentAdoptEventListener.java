package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentAdoptFutureEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class AttachmentAdoptEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentAdoptFutureEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentAdoptFutureEvent> event) {
        try {
            AttachmentAdoptFutureEvent adopt = event.getPayload();
            adopt.result().complete(attachmentService.adopt(adopt.fileName()));
        } catch (Exception e) {
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
