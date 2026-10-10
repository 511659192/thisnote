package com.thisnode.ext.notes.util;

import java.util.UUID;

public final class Ids {

    private Ids() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public static String shortId() {
        return newId().substring(0, 8);
    }
}
