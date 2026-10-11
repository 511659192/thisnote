package com.thisnode.ext.notes.api.dto;

import java.util.List;

public record NoteDetailDto(String id,
                         String title,
                         String type,
                         List<String> cardFileNames,
                         long createdAt,
                         long updatedAt) {
}
