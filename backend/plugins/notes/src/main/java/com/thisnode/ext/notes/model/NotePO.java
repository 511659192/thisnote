package com.thisnode.ext.notes.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public class NotePO {

    private String noteId;
    private String title;
    private String bookId;
    private List<String> cardIds = List.of();
    /** 最近更新时间（epoch 毫秒）：列表按它倒序（原来用 meta 文件 mtime）。 */
    private long updatedAt;
}
