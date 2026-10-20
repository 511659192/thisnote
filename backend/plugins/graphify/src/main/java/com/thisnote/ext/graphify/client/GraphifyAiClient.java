package com.thisnote.ext.graphify.client;

import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GraphifyAiClient {

    private ApplicationEventPublisher eventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public AiRuntimeFutureEvent.Runtime runtime(String profileId) {
        if (eventPublisher == null) {
            throw new IllegalStateException("AI 核心插件尚未就绪");
        }
        AiRuntimeFutureEvent request = AiRuntimeFutureEvent.request(profileId);
        eventPublisher.publishEvent(request);
        if (!request.runtime().isDone()) {
            throw new IllegalStateException("AI 核心插件未响应");
        }
        AiRuntimeFutureEvent.Runtime runtime = request.runtime().getNow(null);
        if (runtime == null) {
            throw new IllegalStateException("AI 核心插件返回了空配置");
        }
        return runtime;
    }

    public List<AiProfileSummary> profiles() {
        if (eventPublisher == null) {
            throw new IllegalStateException("AI 核心插件尚未就绪");
        }
        AiProfilesFutureEvent request = AiProfilesFutureEvent.request();
        eventPublisher.publishEvent(request);
        if (!request.profiles().isDone()) {
            throw new IllegalStateException("AI 核心插件未响应");
        }
        return request.profiles().getNow(List.of());
    }
}
