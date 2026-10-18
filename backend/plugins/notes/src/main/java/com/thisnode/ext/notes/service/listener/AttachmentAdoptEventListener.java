package com.thisnode.ext.notes.service.listener;

import com.thisnode.ext.notes.service.AttachmentService;
import com.thisnode.plugin.api.event.AttachmentAdoptEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 接管未被管理的附件：改名 + 记录原始文件名，返回改名后的附件。
 */
@RequiredArgsConstructor
public class AttachmentAdoptEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentAdoptEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentAdoptEvent> event) {
        try {
            event.getPayload().result().complete(attachmentService.adopt(event.getPayload().fileName()));
        } catch (Exception e) {
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
