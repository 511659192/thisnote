package com.thisnode.ext.notes.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class NoteTitleDefaults {

    private static final DateTimeFormatter TITLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NoteTitleDefaults() {
    }

    public static String currentTimeTitle() {
        return LocalDateTime.now().format(TITLE_TIME_FORMAT);
    }
}
