package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class AttachmentQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentFutureEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentFutureEvent> event) {
        AttachmentFutureEvent query = event.getPayload();
        try {
            var attachments = attachmentService.unmanagedAttachments();
            query.result().complete(attachments);
            if (!attachments.isEmpty()) {
                log.debug("未管理附件查询完成：count={}", attachments.size());
            }
        } catch (Exception e) {
            log.error("未管理附件查询失败", e);
            query.result().completeExceptionally(e);
        }
    }
}
