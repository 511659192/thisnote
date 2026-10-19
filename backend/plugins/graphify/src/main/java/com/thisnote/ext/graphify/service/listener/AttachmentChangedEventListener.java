package com.thisnote.ext.graphify.service.listener;

import com.thisnote.ext.graphify.service.GraphifyIndexService;
import com.thisnote.plugin.api.event.AttachmentChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class AttachmentChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentChangedEvent>> {

    private final GraphifyIndexService indexService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentChangedEvent> event) {
        indexService.onAttachmentChanged(event.getPayload());
    }
}
