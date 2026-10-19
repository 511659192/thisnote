package com.thisnote.ext.tags.service;

import com.thisnote.plugin.api.event.NoteTextChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class NoteTextChangedEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteTextChangedEvent>> {

    private final TagUsageRefreshService tagUsageRefreshService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteTextChangedEvent> event) {
        tagUsageRefreshService.request(event.getPayload().noteId());
    }
}
