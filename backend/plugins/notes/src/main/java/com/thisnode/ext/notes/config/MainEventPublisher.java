package com.thisnode.ext.notes.config;

import org.springframework.context.ApplicationEventPublisher;

/**
 * 主容器 publisher 的持有者 —— 卡片变更事件要从**主容器**发出去才跨得过插件边界。
 *
 * <p>为什么不直接注入 {@code ApplicationEventPublisher}：Spring 在 {@code prepareBeanFactory}
 * 里把它注册成 *resolvable dependency*（指向**当前容器**），注入永远拿到自己所在的容器
 * （实测日志里 {@code publisher == 插件容器} 恒为 true）。包一层自定义类型即可绕开。
 *
 * <p>实例由 {@code NotesPlugin.afterPluginBootstrap} 注册进插件容器，消费方要用
 * {@code ObjectProvider} 延迟取 —— 那个注册发生在容器 refresh 之后。
 */
public record MainEventPublisher(ApplicationEventPublisher publisher) {
}
