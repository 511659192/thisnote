package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginEvent;

/**
 * 插件状态重置：由「状态重置」插件经主容器发布，活动插件清理自己的持久状态。
 *
 * <p>停用插件不启动容器，也不执行状态清理；清理范围由各活动插件的 listener 决定。
 */
public record PluginStateResetEvent() implements PluginEvent {
}
