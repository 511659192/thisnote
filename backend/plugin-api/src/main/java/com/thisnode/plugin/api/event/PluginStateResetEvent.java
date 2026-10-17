package com.thisnode.plugin.api.event;

/**
 * 插件状态重置：由「状态重置」插件经主容器发布，各插件清理自己的持久状态并重新初始化。
 *
 * <p>订阅方把 listener 注册在主容器上（跨插件容器的事件不会传播），收到后自行决定清什么；
 * 平台不参与各插件的业务状态。笔记数据不在清理范围内。
 */
public record PluginStateResetEvent(String sourcePluginId) {

    public PluginStateResetEvent {
        if (sourcePluginId == null || sourcePluginId.isBlank()) {
            throw new IllegalArgumentException("sourcePluginId 不能为空");
        }
    }
}
