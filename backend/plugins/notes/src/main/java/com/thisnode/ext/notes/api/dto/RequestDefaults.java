package com.thisnode.ext.notes.api.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class RequestDefaults {

    private static final DateTimeFormatter TITLE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private RequestDefaults() {
    }

    static String currentTimeTitle() {
        return LocalDateTime.now().format(TITLE_TIME_FORMAT);
    }
}
