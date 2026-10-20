package com.thisnote.ext.notes.listener;

import com.thisnote.ext.notes.service.AttachmentService;
import com.thisnote.plugin.api.event.AttachmentStageFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

import java.nio.file.Path;

@RequiredArgsConstructor
@Slf4j
public class AttachmentStageFutureEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentStageFutureEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentStageFutureEvent> event) {
        AttachmentStageFutureEvent request = event.getPayload();
        try {
            log.debug("处理图片暂存请求：source={}", request.sourcePath());
            request.result().complete(attachmentService.stageImported(Path.of(request.sourcePath())));
        } catch (Exception e) {
            log.error("图片暂存失败：source={}", request.sourcePath(), e);
            request.result().completeExceptionally(e);
        }
    }
}
