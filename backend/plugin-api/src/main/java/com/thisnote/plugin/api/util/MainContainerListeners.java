package com.thisnote.plugin.api.util;

import com.thisnote.plugin.api.event.PluginStateResetEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.context.event.GenericApplicationListener;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 插件跨容器监听器的统一注册口。
 *
 * <p>跨插件事件只在主容器广播，listener 必须挂到主容器；而主容器比插件活得久，
 * 插件停止时不摘除会泄漏、下次启用会重复投递 —— 两个动作必须成对出现，这里把它们收拢，
 * 插件停止时只需调 {@link #removeAll()}。
 */
@Slf4j
public final class MainContainerListeners {

    private final ConfigurableApplicationContext mainContext;
    private final List<ApplicationListener<?>> registered = new ArrayList<>();

    public MainContainerListeners(ConfigurableApplicationContext mainContext) {
        this.mainContext = mainContext;
    }

    public void add(ApplicationListener<?> listener) {
        registered.add(listener);
        mainContext.addApplicationListener(listener);
    }

    /**
     * 注册按 payload 类型分发的监听器：事件到达时把 payload 交给 handler。
     * 类型匹配由 supportsEventType 显式判断，不依赖泛型签名解析。
     */
    public <T> void onPayload(Class<T> payloadType, Consumer<T> handler) {
        add(new PayloadListener<>(payloadType, handler));
    }

    /**
     * 注册状态重置监听：事件到达时执行 reset，doneLog 说明本插件清理了什么。
     */
    public void onStateReset(String doneLog, Runnable reset) {
        add(new PayloadListener<>(PluginStateResetEvent.class, event -> {
            reset.run();
            log.info("收到状态重置事件（来自 {}）：{}", event.sourcePluginId(), doneLog);
        }));
    }

    /**
     * 插件停止时调用：摘除本插件注册的全部监听器。
     */
    public void removeAll() {
        for (ApplicationListener<?> listener : registered) {
            mainContext.removeApplicationListener(listener);
        }
        registered.clear();
    }

    private static final class PayloadListener<T> implements GenericApplicationListener {

        private final Class<T> payloadType;
        private final Consumer<T> handler;

        PayloadListener(Class<T> payloadType, Consumer<T> handler) {
            this.payloadType = payloadType;
            this.handler = handler;
        }

        /** PayloadApplicationEvent 覆写了 getResolvableType，eventType 的泛型参数即 payload 类型。 */
        @Override
        public boolean supportsEventType(ResolvableType eventType) {
            if (eventType.toClass() != PayloadApplicationEvent.class) {
                return false;
            }
            Class<?> payloadClass = eventType.getGeneric().toClass();
            return payloadClass == null || payloadType.isAssignableFrom(payloadClass);
        }

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            handler.accept(payloadType.cast(((PayloadApplicationEvent<?>) event).getPayload()));
        }
    }
}
