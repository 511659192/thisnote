package com.thisnode.ext.notes.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 卡片索引：cardId → 卡片元数据。
 *
 * <p>元数据（几何、归属、颜色…）原来写在卡片 md 的首行，现在只存在这里；md 只保留正文。
 * 文件名就是 {@code meta.sourceFileName}，不另存一份。
 */
@Getter
@Setter
@NoArgsConstructor
public class CardIndex {

    /** 索引结构版本（与 {@link CardPO.CardMeta#SCHEMA} 无关）。 */
    private int v = 1;
    private Map<String, CardPO.CardMeta> cards = new LinkedHashMap<>();
}
