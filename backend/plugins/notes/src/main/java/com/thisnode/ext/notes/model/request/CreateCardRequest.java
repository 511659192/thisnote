package com.thisnode.ext.notes.model.request;

import com.thisnode.ext.notes.model.CardPO;

public record CreateCardRequest(Double x, Double y, Double w, Double h, String content, String color) {
    public CreateCardRequest {
        w = w == null ? CardPO.CardMeta.DEFAULT_W : w;
        h = h == null ? CardPO.CardMeta.DEFAULT_H : h;
        content = content == null ? "" : content;
    }

    public CreateCardRequest() {
        this(null, null, null, null, null, null);
    }
}
