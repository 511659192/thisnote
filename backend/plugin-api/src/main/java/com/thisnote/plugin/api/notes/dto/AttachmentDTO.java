package com.thisnote.plugin.api.notes.dto;

/**
 * 一个「未被管理」的附件：notes 里存在、但没有任何卡片引用。
 *
 * @param fileName 磁盘文件名
 * @param size     文件字节数
 * @param width    图片像素宽度，读取失败时为 0
 * @param height   图片像素高度，读取失败时为 0
 */
public record AttachmentDTO(String fileName, long size, int width, int height) {
}
