package com.thisnote.plugin.api.notes.dto;

import java.util.Map;

public record CardMetaDTO(String cardId,
                          String sourceFileName,
                          String noteId,
                          double x,
                          double y,
                          double w,
                          double h,
                          int z,
                          String color,
                          String status,
                          boolean collapsed,
                          Map<String, Object> ext) {
}
