package com.thisnode.ext.attachmentimporter.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 附件导入状态：已经见过/处理过的附件文件名 → 记录。
 */
@Getter
@Setter
@NoArgsConstructor
public class ImportState {

    private int v = 1;
    private Map<String, ImportedFile> files = new LinkedHashMap<>();

    /**
     * 状态文件里一条附件记录。
     *
     * <p>去重按文件名（键）判断；大小与修改时间只作观测记录，不参与判定。
     */
    @Getter
    @Setter
    public static class ImportedFile {

        private long size;
        private long modifiedAt;
        /** 已导入时：生成的笔记标题。 */
        private String noteTitle;
        /** 规范化改名时：改名前的文件名。 */
        private String sourceFileName;
    }
}
