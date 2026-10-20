package com.thisnote.ext.graphify.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

final class GraphifyChangeQueue {

    private final ScheduledExecutorService worker;
    private final Runnable processor;
    private final Runnable scheduled;
    private final BooleanSupplier ready;
    private final Object lock = new Object();
    private final Set<String> queuedNotes = new LinkedHashSet<>();
    private final Set<String> deletedNotes = new LinkedHashSet<>();
    private final Set<String> unresolvedCardIds = new LinkedHashSet<>();
    private ScheduledFuture<?> queuedChangesTask;
    private boolean stopped;

    GraphifyChangeQueue(ScheduledExecutorService worker,
                        Runnable processor,
                        Runnable scheduled,
                        BooleanSupplier ready) {
        this.worker = worker;
        this.processor = processor;
        this.scheduled = scheduled;
        this.ready = ready;
    }

    void queueNote(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return;
        }
        synchronized (lock) {
            if (!stopped && ready.getAsBoolean() && !deletedNotes.contains(noteId)) {
                queuedNotes.add(noteId);
                schedule();
            }
        }
    }

    void queueNoteDeletion(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return;
        }
        synchronized (lock) {
            if (!stopped && ready.getAsBoolean()) {
                queuedNotes.remove(noteId);
                deletedNotes.add(noteId);
                schedule();
            }
        }
    }

    void queueUnresolvedCard(String cardId) {
        synchronized (lock) {
            if (!stopped && ready.getAsBoolean()) {
                unresolvedCardIds.add(cardId);
                schedule();
            }
        }
    }

    void queueNotesForAttachments(Set<String> fileNames,
                                  Function<String, List<String>> notesForAttachment) {
        synchronized (lock) {
            if (stopped || !ready.getAsBoolean()) {
                return;
            }
            fileNames.forEach(fileName -> queuedNotes.addAll(notesForAttachment.apply(fileName)));
            if (!queuedNotes.isEmpty()) {
                schedule();
            }
        }
    }

    QueueSnapshot snapshotAndClear() {
        synchronized (lock) {
            QueueSnapshot snapshot = new QueueSnapshot(
                    new LinkedHashSet<>(queuedNotes),
                    new LinkedHashSet<>(deletedNotes),
                    new LinkedHashSet<>(unresolvedCardIds));
            queuedNotes.clear();
            deletedNotes.clear();
            unresolvedCardIds.clear();
            return snapshot;
        }
    }

    void restore(QueueSnapshot snapshot) {
        synchronized (lock) {
            queuedNotes.addAll(snapshot.noteIds());
            deletedNotes.addAll(snapshot.deletedNoteIds());
            unresolvedCardIds.addAll(snapshot.unresolvedCardIds());
        }
    }

    void scheduleIfReady() {
        synchronized (lock) {
            if (ready.getAsBoolean() && !isEmpty()) {
                schedule();
            }
        }
    }

    void clear() {
        synchronized (lock) {
            queuedNotes.clear();
            deletedNotes.clear();
            unresolvedCardIds.clear();
            cancelScheduled();
        }
    }

    QueueSnapshot stop() {
        synchronized (lock) {
            stopped = true;
            cancelScheduled();
            return new QueueSnapshot(
                    new LinkedHashSet<>(queuedNotes),
                    new LinkedHashSet<>(deletedNotes),
                    new LinkedHashSet<>(unresolvedCardIds));
        }
    }

    int queuedCount() {
        synchronized (lock) {
            return queuedNotes.size() + deletedNotes.size() + unresolvedCardIds.size();
        }
    }

    private void schedule() {
        cancelScheduled();
        queuedChangesTask = worker.schedule(() -> {
            synchronized (lock) {
                queuedChangesTask = null;
            }
            processor.run();
        }, 2, TimeUnit.SECONDS);
        scheduled.run();
    }

    private void cancelScheduled() {
        if (queuedChangesTask != null) {
            queuedChangesTask.cancel(false);
            queuedChangesTask = null;
        }
    }

    private boolean isEmpty() {
        return queuedNotes.isEmpty() && deletedNotes.isEmpty() && unresolvedCardIds.isEmpty();
    }

    record QueueSnapshot(Set<String> noteIds,
                         Set<String> deletedNoteIds,
                         Set<String> unresolvedCardIds) {

        boolean isEmpty() {
            return noteIds.isEmpty() && deletedNoteIds.isEmpty() && unresolvedCardIds.isEmpty();
        }
    }
}
