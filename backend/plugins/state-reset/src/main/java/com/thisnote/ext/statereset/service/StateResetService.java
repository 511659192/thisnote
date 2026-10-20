package com.thisnote.ext.statereset.service;

import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Publishes the reset event to active plugins. Each listener performs its own
 * cleanup; inactive plugins are not started.
 */
@Slf4j
@Service
public class StateResetService {

    private ApplicationEventPublisher mainEventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    public void resetActivePlugins() {
        if (mainEventPublisher == null) {
            throw new IllegalStateException("事件发布器尚未就绪");
        }
        mainEventPublisher.publishEvent(new PluginStateResetEvent());
        log.info("插件状态重置事件已发布");
    }
}
