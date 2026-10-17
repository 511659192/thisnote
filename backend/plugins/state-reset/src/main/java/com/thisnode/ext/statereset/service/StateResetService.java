package com.thisnode.ext.statereset.service;

import com.thisnode.ext.statereset.StateResetPlugin;
import com.thisnode.plugin.api.event.PluginStateResetEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 发出插件状态重置事件；订阅方（各插件）清理自己的状态。
 */
@Slf4j
@Service
public class StateResetService {

    private ApplicationEventPublisher mainEventPublisher;

    public void setMainEventPublisher(ApplicationEventPublisher publisher) {
        this.mainEventPublisher = Objects.requireNonNull(publisher);
    }

    public void resetAll() {
        mainEventPublisher.publishEvent(new PluginStateResetEvent(StateResetPlugin.PLUGIN_ID));
        log.info("已发出插件状态重置事件");
    }
}
