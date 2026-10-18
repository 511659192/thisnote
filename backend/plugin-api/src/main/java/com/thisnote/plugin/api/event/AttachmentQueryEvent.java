package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.notes.dto.AttachmentDTO;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 未被管理的附件查询：发布方等一份「attachments 目录里、没有任何卡片引用」的附件列表。
 *
 * <p>由核心插件（notes）回答 —— 附件存哪儿、卡片正文怎么引用附件，这两件事归它所有；
 * 导入类插件只拿结果，不解析卡片内容，也不自己扫描附件目录。
 *
 * <p>与 {@link CardQueryEvent} 一样是「发布方等结果」的查询事件，必须经主容器发布，
 * 响应方把 listener 注册在主容器上（原因见 {@link CardChangedEvent}）。
 *
 * <p>找不到响应方时 future 不会被完成，发布方应自行设置超时。
 */
public record AttachmentQueryEvent(CompletableFuture<List<AttachmentDTO>> result) {
}
