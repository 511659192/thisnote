package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginCommand;
import com.thisnote.plugin.api.event.ifaces.PluginNotification;
import com.thisnote.plugin.api.notes.dto.CardDTO;

import java.util.Set;

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
public record CardChangedEvent(String cardId,
                               Kind kind,
                               Set<String> removedAttachmentFileNames,
                               CardCreateRequest createRequest)
        implements PluginNotification,
                   PluginCommand {

    public CardChangedEvent {
        removedAttachmentFileNames = removedAttachmentFileNames == null ? Set.of() : Set.copyOf(removedAttachmentFileNames);
        if (kind == Kind.REQUEST && createRequest == null) {
            throw new IllegalArgumentException("REQUEST 事件必须包含卡片创建申请载荷");
        }
        if (kind != Kind.REQUEST && createRequest != null) {
            throw new IllegalArgumentException("非 REQUEST 事件不能包含卡片创建申请载荷");
        }
    }

    public CardChangedEvent(String cardId,
                            Kind kind) {
        this(cardId, kind, Set.of(), null);
    }

    public CardChangedEvent(String cardId,
                            Kind kind,
                            Set<String> removedAttachmentFileNames) {
        this(cardId, kind, removedAttachmentFileNames, null);
    }

    public static CardChangedEvent requestCardCreate(CardDTO card,
                                                     String noteTitle,
                                                     String bookId) {
        String cardId = card.meta() == null ? null : card.meta().cardId();
        return new CardChangedEvent(cardId, Kind.REQUEST, Set.of(), new CardCreateRequest(card, noteTitle, bookId));
    }

    public enum Kind {
        /**
         * 新建了一张卡片。
         */
        CREATED,
        /**
         * 卡片内容或元数据变了。
         */
        UPDATED,
        /**
         * 卡片没了（用户删除，或外部把 md 文件删掉）。
         */
        DELETED,
        /**
         * 导入插件申请 Notes 创建卡片。
         */
        REQUEST
    }

    public record CardCreateRequest(CardDTO card, String noteTitle, String bookId) {
    }
}
