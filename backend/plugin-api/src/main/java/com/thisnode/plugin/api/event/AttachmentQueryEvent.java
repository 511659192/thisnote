package com.thisnode.plugin.api.event;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 附件引用查询：发布方等一份「当前被任意卡片引用的附件文件名」快照。
 *
 * <p>由核心插件（notes）回答 —— 附件存哪儿、卡片正文怎么引用附件，这两件事归它所有；
 * 导入类插件只拿结果，不解析卡片内容，也不自己判断引用格式。
 *
 * <p>与 {@link CardQueryEvent} 一样是「发布方等结果」的查询事件，必须经主容器发布，
 * 响应方把 listener 注册在主容器上（原因见 {@link CardChangedEvent}）。
 *
 * <p>找不到响应方时 future 不会被完成，发布方应自行设置超时。
 */
public record AttachmentQueryEvent(CompletableFuture<Set<String>> result) {
}
