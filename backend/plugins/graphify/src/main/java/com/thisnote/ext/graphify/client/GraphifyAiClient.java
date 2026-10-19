package com.thisnote.ext.graphify.client;

import com.thisnote.plugin.api.event.AiRuntimeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

@Service
public class GraphifyAiClient {

    private ApplicationEventPublisher eventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public AiRuntimeEvent.Runtime runtime() {
        if (eventPublisher == null) {
            throw new IllegalStateException("AI 核心插件尚未就绪");
        }
        AiRuntimeEvent request = AiRuntimeEvent.request();
        eventPublisher.publishEvent(request);
        if (!request.runtime().isDone()) {
            throw new IllegalStateException("AI 核心插件未响应");
        }
        AiRuntimeEvent.Runtime runtime = request.runtime().getNow(null);
        if (runtime == null) {
            throw new IllegalStateException("AI 核心插件返回了空配置");
        }
        return runtime;
    }
}
