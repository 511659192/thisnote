package com.thisnode.ext.notes.api.dto;

public record NotebookRequest(String title, String parentId, String icon, Integer sortOrder) {
}
