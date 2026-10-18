package com.thisnote.ext.memorycycle.model.view;

import java.util.Map;

public record DueCardView(String cardId,
                          long dueAt,
                          String state,
                          int level,
                          Map<Integer, Long> gradePreview) {
}
