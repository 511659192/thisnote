package com.thisnote.ext.ai.listener;

import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.event.AiRuntimeEvent;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class AiRuntimeEventListener
        implements ApplicationListener<PayloadApplicationEvent<AiRuntimeEvent>> {

    private final AiSettingsService settingsService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AiRuntimeEvent> event) {
        AiRuntimeEvent runtimeEvent = event.getPayload();
        try {
            runtimeEvent.runtime().complete(settingsService.runtime());
        } catch (Exception e) {
            log.error("读取 AI 运行时配置失败", e);
            runtimeEvent.runtime().complete(new AiRuntimeEvent.Runtime("", "", ""));
        }
    }
}
