package com.thisnote.plugin.api.event.ifaces;

import java.util.concurrent.CompletableFuture;

/**
 * 带类型化返回值的跨插件查询或请求。
 *
 * @param <R> 响应类型
 */
public interface PluginFutureEvent<R> extends PluginEvent {

    CompletableFuture<R> result();
}
