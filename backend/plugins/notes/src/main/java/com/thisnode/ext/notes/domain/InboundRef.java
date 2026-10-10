package com.thisnode.ext.notes.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 笔记 meta 里缓存的一条入链：谁在引用我（对端写正文时算好落过来），详情页的
 * "被以下笔记引用"面板直接读，不必查询时扫全库、也不必读对端正文。
 *
 * @param fromId    引用者的笔记 id（标题渲染时按 id 现查，避免改名后缓存陈旧）
 * @param label     链接的显示文字（{@code [[目标|显示文字]]} 的后者）
 * @param context   引用所在的那行 markdown（≤200 字符），引用者写入时截好
 * @param updatedAt 引用者最后写入的时间（面板按它倒序）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InboundRef(String fromId, String label, String context, long updatedAt) {
}
