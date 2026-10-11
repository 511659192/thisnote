package com.thisnode.ext.notes.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public class NoteMeta {
    private int v;
    private String id;
    private String title;
    private String type;
    private String notebookId;
    private List<String> cardPaths;
    private long createdAt = System.currentTimeMillis();
    private long updatedAt = System.currentTimeMillis();
}
