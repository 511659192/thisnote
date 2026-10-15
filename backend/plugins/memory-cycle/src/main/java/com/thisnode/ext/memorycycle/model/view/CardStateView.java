package com.thisnode.ext.memorycycle.model.view;

import java.util.Map;

public record CardStateView(String cardId,
                            boolean enabled,
                            ScheduleView schedule,
                            Map<Integer, Long> gradePreview) {
}
