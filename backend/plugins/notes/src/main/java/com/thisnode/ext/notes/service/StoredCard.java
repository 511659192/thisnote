package com.thisnode.ext.notes.service;

import com.thisnode.ext.notes.domain.Card;
import com.thisnode.ext.notes.domain.Card.CardMeta;
import com.thisnode.ext.notes.domain.NoteMeta;

record StoredCard(NoteMeta note, String fileName, Card card) {
    CardMeta meta() {
        return card.getMeta();
    }
}
