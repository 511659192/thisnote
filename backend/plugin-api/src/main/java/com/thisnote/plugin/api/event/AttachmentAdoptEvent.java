package com.thisnote.plugin.api.event;

import com.thisnote.plugin.api.event.ifaces.PluginQuery;
import com.thisnote.plugin.api.notes.dto.AttachmentDTO;

import java.util.concurrent.CompletableFuture;

/**
 * 接管一个未被管理的附件：请 notes 把它改名为 {@code <uuid>.<ext>}，
 * 并把原始文件名记入附件元数据。
 *
 * <p>由核心插件（notes）回答 —— 附件目录与元数据归它所有。
 * 与 {@link CardQueryEvent} 一样是「发布方等结果」的查询事件，必须经主容器发布；
 * 找不到响应方时 future 不会被完成，发布方应自行设置超时。
 */
public record AttachmentAdoptEvent(CompletableFuture<AttachmentDTO> result,
                                   String fileName) implements PluginQuery<AttachmentDTO> {
}
