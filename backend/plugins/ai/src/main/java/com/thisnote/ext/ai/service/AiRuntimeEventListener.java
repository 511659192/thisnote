package com.thisnote.ext.ai.service;

import com.thisnote.plugin.api.event.AiRuntimeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class AiRuntimeEventListener
        implements ApplicationListener<PayloadApplicationEvent<AiRuntimeEvent>> {

    private final AiSettingsService settingsService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AiRuntimeEvent> event) {
        AiRuntimeEvent runtimeEvent = event.getPayload();
        try {
            runtimeEvent.runtime().complete(settingsService.runtime());
        } catch (RuntimeException e) {
            runtimeEvent.runtime().complete(new AiRuntimeEvent.Runtime("", "", ""));
        }
    }
}
