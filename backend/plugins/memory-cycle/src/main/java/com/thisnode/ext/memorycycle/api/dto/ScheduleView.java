package com.thisnode.ext.memorycycle.api.dto;

/** The API representation of one card's schedule; due status is computed, not stored. */
public record ScheduleView(String cardId,
                           String state,
                           int level,
                           long dueAt,
                           Long lastReviewedAt,
                           int reps,
                           int lapses,
                           boolean due) {
}
