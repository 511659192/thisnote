package com.thisnode.ext.notes.api.dto;

public record UpdateNotebookRequest(String title, String parentId, String icon, Integer sortOrder) {
}
