package com.thisnote.ext.ai.listener;

import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class AiProfilesEventListener
        implements ApplicationListener<PayloadApplicationEvent<AiProfilesFutureEvent>> {

    private final AiSettingsService settingsService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AiProfilesFutureEvent> event) {
        AiProfilesFutureEvent request = event.getPayload();
        try {
            var profiles = settingsService.summaries();
            request.profiles().complete(profiles);
            log.debug("AI profile 列表已返回：count={}", profiles.size());
        } catch (Exception e) {
            log.error("读取 AI profile 列表失败", e);
            request.profiles().completeExceptionally(e);
        }
    }
}
