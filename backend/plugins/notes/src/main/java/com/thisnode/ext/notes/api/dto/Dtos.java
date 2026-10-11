package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Card;

import java.util.List;

/**
 * API payloads shared with the notes client.
 */
public final class Dtos {

    private Dtos() {
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
    }

    public record UpdateNodeRequest(String title, String contentMd, String source, String status, String notebookId, Boolean clearNotebook) {
    }

    public record CardView(Card.CardMeta card, String contentMd, String noteTitle) {
    }

    public record CardsDto(String noteId, String noteTitle, List<CardView> cards, int maxCardsPerNote, long updatedAt) {
    }

    public record CreateCardRequest(Double x, Double y, Double w, Double h, String contentMd, String title, String color) {
    }

    public record WriteContentRequest(String contentMd, String baseContentMd) {
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
    }

}
