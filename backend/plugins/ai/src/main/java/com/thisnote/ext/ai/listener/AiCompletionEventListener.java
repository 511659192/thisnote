package com.thisnote.ext.ai.listener;

import com.thisnote.ext.ai.client.AiProviderClient;
import com.thisnote.ext.ai.service.AiCompletionExecutor;
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
    private final AiCompletionExecutor executor;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AiCompletionFutureEvent> event) {
        AiCompletionFutureEvent request = event.getPayload();
        try {
            executor.execute(() -> run(request));
        } catch (Exception e) {
            log.error("AI completion 无法排队：profileId={}", request.profileId(), e);
            request.completion().completeExceptionally(e);
        }
    }

    private void run(AiCompletionFutureEvent request) {
        if (request.completion().isDone()) {
            log.debug("AI completion 已超时或被取消，跳过执行：profileId={}", request.profileId());
            return;
        }
        try {
            AiRuntimeFutureEvent.Runtime runtime = settingsService.runtime(request.profileId());
            if (!runtime.configured()) {
                throw new IllegalStateException("AI profile「" + request.profileId() + "」尚未配置完整");
            }

            String text = truncate(request.userText(), runtime.maxTextChars());
            String response = providerClient.complete(runtime, request.systemPrompt(), text, request.images(), request.maxTokens());
            request.completion().complete(response);
            log.debug("AI completion 完成：profileId={}, responseLength={}", request.profileId(), response == null ? 0 : response.length());
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
