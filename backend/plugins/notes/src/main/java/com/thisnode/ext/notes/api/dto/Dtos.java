package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.store.CardRef;

import java.util.List;
import java.util.Map;

/**
 * API payloads. Kept as records so the JSON contract is explicit and stable for the
 * Vue frontend (frontend/src/api/types.ts mirrors these).
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
    public record BacklinkDto(String noteId, String title, String label, String heading,
                              String context, long updatedAt) {
    }

    public record LinkDto(String id, String fromNodeId, String toNodeId, String toTitle,
                          String label, String type, boolean resolved) {
    }

    public record NoteDetail(
            String id, String title, String contentMd, String type, String source, String status,
            boolean deleted, String dailyDate,
            /**
             * 卡片清单（只有身份）：页面级数据（坐标、正文）按需取，
             * 前端拿这份清单去 {@code GET /api/notes/{id}/cards?ids=}。
             */
            List<CardRef> cards,
            List<String> tags, List<NotebookDto> notebooks,
            List<LinkDto> outbound, List<BacklinkDto> backlinks,
            List<String> unresolved, List<String> unlinkedMentions,
            long createdAt, long updatedAt) {
    }

    public record CreateNodeRequest(String title, String contentMd, String type, String dailyDate,
                                    List<String> tags, String source,
                                    String status, String notebookId) {
    }

    public record UpdateNodeRequest(String title, String contentMd, String source, String status,
                                    List<String> tags, String notebookId,
                                    Boolean clearNotebook) {
    }

    // ---------- note page cards ----------
    //
    // A note's page is made of cards. A card holds a fragment of *that note's* markdown
    // plus its position on the page. There is no board/container entity: the note is the
    // container, which is why these records carry noteId rather than boardId.

    /**
     * A card on a note's page.
     *
     * <p>{@code contentMd} is this card's own fragment of the note. The note's markdown is the
     * concatenation of its cards in order, so there is one copy of the content and a card's
     * text never shifts when a neighbouring card grows.
     *
     * <p>{@code title} is an optional label the *user* gives the card. It is not part of the
     * note's content and stays empty unless the user names the card; when empty the UI shows
     * no title bar at all.
     */
    public record CardDto(String id, String noteId, String title,
                               double x, double y, double w, double h, int z,
                               String color, String status, boolean collapsed,
                               String contentMd, long createdAt, long updatedAt) {
    }

    /**
     * A card together with the note-level context it renders with.
     *
     * <p>A card's own text and title live on the block itself (one copy), so this view only
     * adds the note context the card needs for rendering.
     */
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

    /**
     * 卡片正文保存：新正文 + 客户端**编辑时所依据**的正文。
     *
     * <p>后者是外部改动检测的基线（{@code null} = 不带基线，旧客户端保持原行为）：磁盘上已经不是
     * 那一份就返回 409，而不是把外部编辑器写的字覆盖掉。
     */
    public record WriteContentRequest(String contentMd, String baseContentMd) {
    }

    public record CardPatch(String id, String title, Double x, Double y, Double w, Double h,
                             Integer z, String color, String status, Boolean collapsed) {
    }

    public record BatchCardPatchRequest(List<CardPatch> cards) {
    }


    public record AttachmentDto(String name, String url, long size) {
    }

    // ---------- ops / system ----------
    public record OpDto(String id, String entityType, String entityId, String action,
                        String payload, long createdAt) {
    }

    public record SystemInfo(String version, String dbPath, String markdownRoot,
                             String attachmentRoot, long nodeCount,
                             long noteBlockCount, long uptimeMs) {
    }
}
