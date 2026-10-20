package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginEvent;

/**
 * Graphify 图谱内容已更新，消费方应失效基于旧图谱生成的数据。
 */
public record GraphifyIndexCompletedEvent(String completedAt) implements PluginEvent {

    public GraphifyIndexCompletedEvent {
        completedAt = completedAt == null ? "" : completedAt;
    }
}
