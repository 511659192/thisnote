package com.thisnode.plugin.api.notes.dto;

import java.util.List;

public record NoteDTO(String noteId, String title, String bookId, List<String> cardIds) {
}
