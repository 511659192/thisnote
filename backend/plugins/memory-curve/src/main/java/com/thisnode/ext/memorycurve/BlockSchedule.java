package com.thisnode.ext.memorycurve;

/**
 * One card's spaced-repetition state (the memory-curve plugin's own table, {@code block_scheduling}).
 */
public class BlockSchedule {

    private String blockId;
    /** new / learning / review / relearning */
    private String state = "new";
    private double ease = 2.5;
    private double intervalDays = 0;
    private long dueAt;
    private Long lastReviewedAt;
    private int reps;
    private int lapses;
    private int learningStep;
    private long createdAt;
    private long updatedAt;

    public String getBlockId() {
        return blockId;
    }

    public void setBlockId(String blockId) {
        this.blockId = blockId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public double getEase() {
        return ease;
    }

    public void setEase(double ease) {
        this.ease = ease;
    }

    public double getIntervalDays() {
        return intervalDays;
    }

    public void setIntervalDays(double intervalDays) {
        this.intervalDays = intervalDays;
    }

    public long getDueAt() {
        return dueAt;
    }

    public void setDueAt(long dueAt) {
        this.dueAt = dueAt;
    }

    public Long getLastReviewedAt() {
        return lastReviewedAt;
    }

    public void setLastReviewedAt(Long lastReviewedAt) {
        this.lastReviewedAt = lastReviewedAt;
    }

    public int getReps() {
        return reps;
    }

    public void setReps(int reps) {
        this.reps = reps;
    }

    public int getLapses() {
        return lapses;
    }

    public void setLapses(int lapses) {
        this.lapses = lapses;
    }

    public int getLearningStep() {
        return learningStep;
    }

    public void setLearningStep(int learningStep) {
        this.learningStep = learningStep;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
