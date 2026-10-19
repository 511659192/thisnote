package com.thisnote.plugin.api.notes.dto;

import java.util.Map;

public record CardDTO(CardMetaDTO meta, String content, String sourceFileName) {
    public static record CardMetaDTO(String cardId, String sourceFileName, String noteId, double x, double y, double w, double h, String color, String status, boolean collapsed, Map<String, Object> ext) {
    }

    /**
     * 一个「未被管理」的附件：notes 里存在、但没有任何卡片引用。
     *
     * @param fileName 磁盘文件名
     * @param size     文件字节数
     * @param width    图片像素宽度，读取失败时为 0
     * @param height   图片像素高度，读取失败时为 0
     */
    public static record AttachmentDTO(String fileName, long size, int width, int height) {
    }
}
