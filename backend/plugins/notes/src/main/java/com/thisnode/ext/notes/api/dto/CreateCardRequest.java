package com.thisnode.ext.notes.api.dto;

import com.thisnode.ext.notes.domain.Card;

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
