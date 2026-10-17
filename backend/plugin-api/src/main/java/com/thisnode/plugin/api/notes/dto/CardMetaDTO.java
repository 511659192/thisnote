package com.thisnode.plugin.api.notes.dto;

import java.util.Map;

public record CardMetaDTO(int v,
                          String cardId,
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
