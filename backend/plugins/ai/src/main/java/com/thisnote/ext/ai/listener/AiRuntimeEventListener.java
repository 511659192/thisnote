package com.thisnote.ext.ai.listener;

import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class AiRuntimeEventListener
        implements ApplicationListener<PayloadApplicationEvent<AiRuntimeFutureEvent>> {

    private final AiSettingsService settingsService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AiRuntimeFutureEvent> event) {
        AiRuntimeFutureEvent runtimeEvent = event.getPayload();
        try {
            runtimeEvent.runtime().complete(settingsService.runtime(runtimeEvent.profileId()));
            log.debug("AI 运行时配置已返回：profileId={}", runtimeEvent.profileId());
        } catch (Exception e) {
            log.error("读取 AI 运行时配置失败：profileId={}", runtimeEvent.profileId(), e);
            runtimeEvent.runtime().completeExceptionally(e);
        }
    }
}
