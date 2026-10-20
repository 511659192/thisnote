package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentBytesFutureEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.nio.file.Files;
import java.nio.file.Path;

@RequiredArgsConstructor
public class AttachmentBytesQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentBytesFutureEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentBytesFutureEvent> event) {
        AttachmentBytesFutureEvent query = event.getPayload();
        Path path = attachmentService.existingPath(query.fileName());
        if (path == null) {
            query.result().complete(null);
            return;
        }
        try {
            query.result().complete(Files.readAllBytes(path));
        } catch (Exception e) {
            query.result().completeExceptionally(e);
        }
    }
}
