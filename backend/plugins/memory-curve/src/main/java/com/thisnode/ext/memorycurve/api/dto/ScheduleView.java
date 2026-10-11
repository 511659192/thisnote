package com.thisnode.ext.memorycurve.api.dto;

/** The API representation of one card's schedule; due status is computed, not stored. */
public record ScheduleView(String cardId,
                           String state,
                           double ease,
                           double intervalDays,
                           long dueAt,
                           Long lastReviewedAt,
                           int reps,
                           int lapses,
                           boolean due) {
}
