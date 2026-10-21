package com.thisnote.ext.aititle.listener;

import com.thisnote.ext.aititle.service.TitleSuggestionService;
import com.thisnote.plugin.api.event.NoteTitleSuggestionFutureEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@Slf4j
@RequiredArgsConstructor
public class NoteTitleSuggestionEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteTitleSuggestionFutureEvent>> {

    private final TitleSuggestionService suggestionService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteTitleSuggestionFutureEvent> event) {
        NoteTitleSuggestionFutureEvent request = event.getPayload();
        try {
            request.result().complete(suggestionService.suggest(
                    request.fallbackTitle(), request.contentMd()));
        } catch (Exception e) {
            log.error("AI 标题请求处理失败：title={}", request.fallbackTitle(), e);
            request.result().complete(request.fallbackTitle());
        }
    }
}
