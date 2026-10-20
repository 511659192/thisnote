package com.thisnote.ext.graphify.client;

import com.thisnote.plugin.api.event.AiProfileSummary;
import com.thisnote.plugin.api.event.AiProfilesFutureEvent;
import com.thisnote.plugin.api.event.AiRuntimeFutureEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class GraphifyAiClient {

    private ApplicationEventPublisher eventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public AiRuntimeFutureEvent.Runtime runtime(String profileId) {
        if (eventPublisher == null) {
            log.warn("读取 Graphify AI 运行时配置失败：AI 核心插件尚未就绪");
            throw new IllegalStateException("AI 核心插件尚未就绪");
        }

        log.debug("请求 Graphify AI 运行时配置：profileId={}", profileId);
        AiRuntimeFutureEvent request = AiRuntimeFutureEvent.request(profileId);
        eventPublisher.publishEvent(request);
        if (!request.runtime().isDone()) {
            throw new IllegalStateException("AI 核心插件未响应");
        }

        AiRuntimeFutureEvent.Runtime runtime = request.runtime().getNow(null);
        if (runtime == null) {
            throw new IllegalStateException("AI 核心插件返回了空配置");
        }

        log.debug("Graphify AI 运行时配置已获取：profileId={}, provider={}, model={}, endpoint={}",
                  profileId, runtime.provider(), runtime.model(), runtime.endpoint());
        return runtime;
    }

    public List<AiProfileSummary> profiles() {
        if (eventPublisher == null) {
            log.warn("读取 Graphify AI profile 列表失败：AI 核心插件尚未就绪");
            throw new IllegalStateException("AI 核心插件尚未就绪");
        }

        AiProfilesFutureEvent request = AiProfilesFutureEvent.request();
        eventPublisher.publishEvent(request);
        if (!request.profiles().isDone()) {
            throw new IllegalStateException("AI 核心插件未响应");
        }

        List<AiProfileSummary> profiles = request.profiles().getNow(List.of());
        log.debug("Graphify AI profile 列表已获取：count={}", profiles.size());
        return profiles;
    }
}
