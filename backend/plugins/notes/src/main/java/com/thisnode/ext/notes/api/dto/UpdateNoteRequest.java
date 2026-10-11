package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.util.NoteTitleDefaults;

public record UpdateNoteRequest(String title) {
    public UpdateNoteRequest {
        if (title != null && title.isBlank()) {
            title = NoteTitleDefaults.currentTimeTitle();
        }
    }
}
