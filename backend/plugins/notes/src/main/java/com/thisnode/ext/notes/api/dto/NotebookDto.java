package com.thisnode.ext.notes.api.dto;

public record NotebookDto(String id,
                          String type,
                          String title,
                          String parentId,
                          String icon,
                          Integer sortOrder,
                          Integer count,
                          Long createdAt,
                          Long updatedAt) {
}
