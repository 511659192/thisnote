package com.thisnote.ext.ai.service;

import com.thisnote.plugin.api.event.NoteTagSuggestionEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

@RequiredArgsConstructor
public class NoteTagSuggestionEventListener
        implements ApplicationListener<PayloadApplicationEvent<NoteTagSuggestionEvent>> {

    private final AiTagSuggestionService aiTagSuggestionService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<NoteTagSuggestionEvent> event) {
        aiTagSuggestionService.suggest(event.getPayload());
    }
}
