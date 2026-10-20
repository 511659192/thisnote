package com.thisnote.ext.ai.listener;

import com.thisnote.ext.ai.client.AiProviderClient;
import com.thisnote.ext.ai.service.AiSettingsService;
import com.thisnote.plugin.api.event.AiCompletionFutureEvent;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
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
            AiRuntimeFutureEvent.Runtime runtime = settingsService.runtime(request.profileId());
            if (!runtime.configured()) {
                throw new IllegalStateException("AI profile「" + request.profileId() + "」尚未配置完整");
            }
            String text = truncate(request.userText(), runtime.maxTextChars());
            request.completion().complete(providerClient.complete(
                    runtime,
                    request.systemPrompt(),
                    text,
                    request.images(),
                    request.maxTokens()
            ));
        } catch (Exception e) {
            log.error("AI completion 失败：profileId={}", request.profileId(), e);
            request.completion().completeExceptionally(e);
        }
    }

    private static String truncate(String value,
                                   int maxChars) {
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
