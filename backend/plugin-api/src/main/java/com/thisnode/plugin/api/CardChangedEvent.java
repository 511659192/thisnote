package com.thisnode.plugin.api;

/**
 * 卡片变更事件：核心插件（notes）发布，兄弟插件可订阅。
 *
 * <p><b>为什么必须经主容器中转</b>（实测，不是推测）：SBP 给每个插件一个独立的
 * {@code ApplicationContext}（主应用是 {@code AnnotationConfigServletWebServerApplicationContext}，
 * 插件是 {@code AnnotationConfigApplicationContext}），Spring 的事件只在**发布它的容器**内广播 ——
 * 插件 A 在自己容器里 {@code publishEvent}，插件 B 容器里的 listener 收不到；把同一个 listener
 * 注册到主容器、并从主容器发布，才能收到。
 *
 * <p>因此约定：**发布方拿主容器发，订阅方把 listener 注册到主容器**
 * （{@code SpringBootPlugin#getMainApplicationContext()}）。
 *
 * <p>事件本身是纯数据 record，按 Spring 4.2+ 的 payload 模式监听
 * （{@code publishEvent(Object)} ↔ {@code ApplicationListener<PayloadApplicationEvent<CardChangedEvent>>}），
 * 不必让事件类继承 {@code ApplicationEvent}。
 */
public record CardChangedEvent(String cardId, Kind kind) {

    public enum Kind {
        /** 新建了一张卡片。 */
        CREATED,
        /** 卡片内容或元数据变了。 */
        UPDATED,
        /** 卡片没了（用户删除，或外部把 md 文件删掉）。 */
        DELETED
    }
}
