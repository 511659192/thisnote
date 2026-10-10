package com.thisnode.ext.notes.api.dto;

import java.util.List;

/**
 * API payloads. Kept as records so the JSON contract is explicit and stable for the
 * The UI mirrors these shapes (see the notes client types).
 */
public final class Dtos {

    private Dtos() {
    }

    // ---------- tags ----------
    public record TagDto(String id, String name, String color, Integer count) {
    }

    // ---------- notebooks ----------
    public record NotebookDto(String id, String type, String title, String parentId, String icon,
                               Integer sortOrder, Integer count, Long createdAt, Long updatedAt) {
    }

    public record NotebookRequest(String title, String parentId, String icon, Integer sortOrder) {
    }

    // ---------- nodes ----------
    public record NoteDetail(
            String id, String title, String contentMd, String type,
            /**
             * 卡片 ID 清单：页面级数据（坐标、正文）按需取，
             * 界面侧用这些 ID 请求 {@code GET /api/notes/cards?noteId=}。
             */
            List<String> cards,
            List<String> tags, List<NotebookDto> notebooks,
            List<String> unresolved, List<String> unlinkedMentions,
            long createdAt, long updatedAt) {
    }

    public record CreateNodeRequest(String title, String contentMd, String type,
                                    List<String> tags, String notebookId) {
    }

    public record UpdateNodeRequest(String title, String contentMd, String source, String status,
                                    List<String> tags, String notebookId,
                                    Boolean clearNotebook) {
    }

    public record CardDto(String id, String noteId, String title,
                               double x, double y, double w, double h, int z,
                               String color, String status, boolean collapsed,
                               String contentMd, long createdAt, long updatedAt) {
    }


    public record CardView(CardDto card, String contentMd, String noteTitle,
                                List<TagDto> tags) {
    }

    public record CardsDto(String noteId, String noteTitle,
                                List<CardView> cards, List<TagDto> tags,
                                int maxCardsPerNote, long updatedAt) {
    }

    public record CreateCardRequest(Double x, Double y, Double w, Double h,
                                     String contentMd, String title, String color) {
    }

    public record WriteContentRequest(String contentMd, String baseContentMd) {
    }

    public record CardUpdate(String id, String title, Double x, Double y, Double w, Double h,
                             Integer z, String color, String status, Boolean collapsed) {
    }

    public record BatchCardUpdateRequest(List<CardUpdate> cards) {
    }


    public record AttachmentDto(String name, String url, long size) {
    }
}
