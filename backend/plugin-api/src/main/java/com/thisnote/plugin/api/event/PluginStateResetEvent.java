package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginControl;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 插件状态重置：由「状态重置」插件经主容器发布，活动插件清理自己的持久状态并回报结果。
 *
 * <p>停用插件不启动容器，也不执行状态清理；笔记数据不在清理范围内。
 */
public record PluginStateResetEvent(String sourcePluginId, Map<String, CompletableFuture<PluginResetResult>> participants)
        implements PluginControl {

    public PluginStateResetEvent {
        if (sourcePluginId == null || sourcePluginId.isBlank()) {
            throw new IllegalArgumentException("sourcePluginId 不能为空");
        }
        participants = participants == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(participants));
    }

    public boolean expects(String pluginId) {
        return participants.containsKey(pluginId);
    }

    public void complete(String pluginId,
                         PluginResetResult result) {
        CompletableFuture<PluginResetResult> target = participants.get(pluginId);
        if (target == null) {
            throw new IllegalArgumentException("重置事件不包含插件：" + pluginId);
        }
        target.complete(result);
    }
}
