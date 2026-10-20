package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentFutureEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class AttachmentQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentFutureEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentFutureEvent> event) {
        try {
            event.getPayload().result().complete(attachmentService.unmanagedAttachments());
        } catch (Exception e) {
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
