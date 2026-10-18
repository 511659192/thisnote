package com.thisnode.plugin.api.notes.dto;

public record BookDTO(String id, String type, String title, String icon, int sortOrder, long createdAt, long updatedAt) {
}
