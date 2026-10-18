package com.thisnote.ext.notes.model.request;

import java.util.List;

public record DeleteNotesRequest(List<String> noteIds) {
}
