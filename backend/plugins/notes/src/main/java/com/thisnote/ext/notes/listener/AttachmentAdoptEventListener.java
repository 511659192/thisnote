package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentAdoptFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
@Slf4j
public class AttachmentAdoptEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentAdoptFutureEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentAdoptFutureEvent> event) {
        AttachmentAdoptFutureEvent adopt = event.getPayload();
        try {
            log.debug("处理附件接管请求：fileName={}", adopt.fileName());
            var adopted = attachmentService.adopt(adopt.fileName());
            adopt.result().complete(adopted);
            if (adopted == null) {
                log.warn("附件接管未完成：fileName={}, reason=不存在或文件名非法", adopt.fileName());
            }
        } catch (Exception e) {
            log.error("附件接管失败：fileName={}", adopt.fileName(), e);
            adopt.result().completeExceptionally(e);
        }
    }
}
