package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Card;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * API payloads shared with the notes client.
 */
public final class Dtos {

    private static final DateTimeFormatter TITLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private Dtos() {
    }

    private static String currentTimeTitle() {
        return LocalDateTime.now().format(TITLE_TIME_FORMAT);
    }

    public record NotebookDto(String id,
                              String type,
                              String title,
                              String parentId,
                              String icon,
                              Integer sortOrder,
                              Integer count,
                              Long createdAt,
                              Long updatedAt) {
    }

    public record NotebookCreateRequest(String title, String icon) {
        public NotebookCreateRequest {
            title = title == null || title.isBlank() ? "新建笔记本" : title.trim();
        }
    }

    public record NotebookRequest(String title, String parentId, String icon, Integer sortOrder) {
    }

    public record NoteDetail(String id,
                             String title,
                             String contentMd,
                             String type,
                             List<String> cards,
                             List<NotebookDto> notebooks,
                             long createdAt,
                             long updatedAt) {
    }

    public record CreateNodeRequest(String title, String contentMd, String type, String notebookId) {
        public CreateNodeRequest {
            title = title == null ? "" : title.trim();
            title = title.isBlank() ? currentTimeTitle() : title;
            contentMd = contentMd == null ? "" : contentMd;
            type = type == null || type.isBlank() ? "note" : type;
            notebookId = notebookId == null || notebookId.isBlank() ? null : notebookId;
        }
    }

    public record UpdateNodeRequest(String title, String contentMd, String source, String status) {
        public UpdateNodeRequest {
            if (title != null && title.isBlank()) {
                title = currentTimeTitle();
            }
        }
    }

    public record CardView(Card.CardMeta card, String contentMd, String noteTitle) {
    }

    public record CardsDto(String noteId, String noteTitle, List<CardView> cards, int maxCardsPerNote, long updatedAt) {
    }

    public record CreateCardRequest(Double x, Double y, Double w, Double h, String contentMd, String title, String color) {
        public CreateCardRequest {
            w = w == null ? Card.CardMeta.DEFAULT_W : w;
            h = h == null ? Card.CardMeta.DEFAULT_H : h;
            contentMd = contentMd == null ? "" : contentMd;
            title = title == null ? "" : title.trim();
        }

        public CreateCardRequest() {
            this(null, null, null, null, null, null, null);
        }
    }

    public record WriteContentRequest(String contentMd, String baseContentMd) {
        public WriteContentRequest {
            contentMd = contentMd == null ? "" : contentMd;
        }

        public WriteContentRequest() {
            this(null, null);
        }
    }

    public record CardUpdate(String id,
                             String title,
                             Double x,
                             Double y,
                             Double w,
                             Double h,
                             String color,
                             String status,
                             Boolean collapsed) {
    }

    public record BatchCardUpdateRequest(List<CardUpdate> cards) {
        public BatchCardUpdateRequest {
            cards = cards == null ? List.of() : cards;
        }
    }

}
