package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentBytesFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.nio.file.Files;
import java.nio.file.Path;

@RequiredArgsConstructor
@Slf4j
public class AttachmentBytesQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentBytesFutureEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentBytesFutureEvent> event) {
        AttachmentBytesFutureEvent query = event.getPayload();
        Path path = attachmentService.existingPath(query.fileName());
        if (path == null) {
            log.debug("附件字节查询未命中：fileName={}", query.fileName());
            query.result().complete(null);
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            query.result().complete(bytes);
            log.debug("附件字节查询完成：fileName={}, bytes={}", query.fileName(), bytes.length);
        } catch (Exception e) {
            log.error("读取附件字节失败：fileName={}, path={}", query.fileName(), path, e);
            query.result().completeExceptionally(e);
        }
    }
}
