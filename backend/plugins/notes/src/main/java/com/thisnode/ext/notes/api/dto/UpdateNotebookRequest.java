package com.thisnode.ext.notes.api.dto;

public record UpdateNotebookRequest(String title, String icon, Integer sortOrder) {
}
