package com.thisnote.ext.notes.model.request;

import java.util.List;

public record MoveNotesRequest(List<String> noteIds, String bookId) {
}
