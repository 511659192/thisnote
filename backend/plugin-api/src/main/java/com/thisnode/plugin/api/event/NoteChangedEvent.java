package com.thisnode.plugin.api.event;

import com.thisnode.plugin.api.notes.dto.CardDTO;

import java.util.List;

/** Cross-plugin note lifecycle and creation-request event. */
public record NoteChangedEvent(String noteId,
                               Kind kind,
                               String title,
                               List<String> cardIds,
                               NoteCreateRequest createRequest) {

    public NoteChangedEvent {
        cardIds = cardIds == null ? List.of() : List.copyOf(cardIds);
        if (kind == Kind.REQUEST && createRequest == null) {
            throw new IllegalArgumentException("REQUEST 事件必须包含笔记创建申请载荷");
        }
        if (kind != Kind.REQUEST && createRequest != null) {
            throw new IllegalArgumentException("非 REQUEST 事件不能包含笔记创建申请载荷");
        }
    }

    public NoteChangedEvent(String noteId, Kind kind, String title, List<String> cardIds) {
        this(noteId, kind, title, cardIds, null);
    }

    public static NoteChangedEvent requestNoteCreate(CardDTO card,
                                                     String noteTitle,
                                                     String bookId) {
        return new NoteChangedEvent(null, Kind.REQUEST, noteTitle, List.of(),
                new NoteCreateRequest(card, noteTitle, bookId));
    }

    public enum Kind {
        CREATED,
        TITLE_CHANGED,
        DELETED,
        REQUEST
    }

    public record NoteCreateRequest(CardDTO card,
                                   String noteTitle,
                                   String bookId) {
    }
}
