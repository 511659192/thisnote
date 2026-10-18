package com.thisnode.ext.notes.service.listener;

import com.thisnode.ext.notes.service.AttachmentService;
import com.thisnode.plugin.api.event.AttachmentQueryEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;

/**
 * 回答「当前未被管理的附件」（attachments 目录里、没有任何卡片引用的 png/jpg）。
 *
 * <p>实例注册在**主容器**上（跨插件容器的事件不会传播），由 {@code NotesPlugin} 在
 * {@code afterPluginBootstrap} 里挂上、{@code stop()} 里摘掉。
 */
@RequiredArgsConstructor
public class AttachmentQueryEventListener
        implements ApplicationListener<PayloadApplicationEvent<AttachmentQueryEvent>> {

    private final AttachmentService attachmentService;

    @Override
    public void onApplicationEvent(PayloadApplicationEvent<AttachmentQueryEvent> event) {
        try {
            event.getPayload().result().complete(attachmentService.unmanagedAttachments());
        } catch (Exception e) {
            // 读取卡片/目录时可能撞上正在写入/被删除的文件，按查询失败处理，不打断事件总线
            event.getPayload().result().completeExceptionally(e);
        }
    }
}
