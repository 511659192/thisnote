package com.thisnode.plugin.api.notes.dto;

public record NotebookDTO(int v,
                          String id,
                          String type,
                          String title,
                          String icon,
                          int sortOrder,
                          long createdAt,
                          long updatedAt) {
}
