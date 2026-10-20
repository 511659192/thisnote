package com.thisnote.ext.ai.listener;

import com.thisnote.ext.ai.client.AiProviderClient;
import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AiRuntimeFututeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class AiCompletionEventListener
        implements ApplicationListener<PayloadApplicationEvent<AiCompletionFutureEvent>> {

    private final AiSettingsService settingsService;
    private final AiProviderClient providerClient;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AiCompletionFutureEvent> event) {
        AiCompletionFutureEvent request = event.getPayload();
        try {
            AiRuntimeFututeEvent.Runtime runtime = settingsService.runtime();
            if (!runtime.configured()) {
                request.completion().complete("");
                return;
            }
            String text = truncate(request.userText(), settingsService.settings().maxTextChars());
            request.completion().complete(providerClient.complete(
                    runtime,
                    request.systemPrompt(),
                    text,
                    request.images(),
                    request.maxTokens()
            ));
        } catch (Exception e) {
            log.error("AI completion 失败", e);
            request.completion().complete("");
        }
    }

    private static String truncate(String value,
                                   int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
