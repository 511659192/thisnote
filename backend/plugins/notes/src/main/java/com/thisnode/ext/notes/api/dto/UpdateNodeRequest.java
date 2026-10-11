package com.thisnode.ext.notes.api.dto;

public record UpdateNodeRequest(String title) {
    public UpdateNodeRequest {
        if (title != null && title.isBlank()) {
            title = RequestDefaults.currentTimeTitle();
        }
    }
}
