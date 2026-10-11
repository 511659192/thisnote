package com.thisnode.ext.notes.api.dto;

import java.util.List;

public record NoteDetail(String id,
                         String title,
                         String type,
                         List<String> cards,
                         List<NotebookDto> notebooks,
                         long createdAt,
                         long updatedAt) {
}
