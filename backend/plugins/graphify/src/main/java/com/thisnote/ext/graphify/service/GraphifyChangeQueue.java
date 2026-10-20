package com.thisnote.ext.graphify.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * 把 note/card/attachment 变更收集成一个防抖批次。
 *
 * <p>处理器是单线程的，但事件来自多个插件线程，因此所有可变队列状态
 * 都由同一把锁保护；定时任务只把快照交给索引服务。
 */
@Slf4j
@RequiredArgsConstructor
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

    void queueNote(String noteId) {
        if (noteId == null || noteId.isBlank()) {
            return;
        }
        synchronized (lock) {
            if (!stopped && ready.getAsBoolean() && !deletedNotes.contains(noteId)) {
                queuedNotes.add(noteId);
                log.debug("Graphify 变更入队：type=note, noteId={}, queued={}", noteId, queuedNotes.size());
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
                log.debug("Graphify 变更入队：type=delete, noteId={}, deleted={}", noteId, deletedNotes.size());
                schedule();
            }
        }
    }

    void queueUnresolvedCard(String cardId) {
        synchronized (lock) {
            if (!stopped && ready.getAsBoolean()) {
                unresolvedCardIds.add(cardId);
                log.debug("Graphify 变更入队：type=unresolved-card, cardId={}", cardId);
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
            int before = queuedNotes.size();
            fileNames.forEach(fileName -> queuedNotes.addAll(notesForAttachment.apply(fileName)));
            log.debug("Graphify 附件变更映射入队：files={}, notesAdded={}", fileNames.size(), queuedNotes.size() - before);
            if (!queuedNotes.isEmpty()) {
                schedule();
            }
        }
    }

    QueueSnapshot snapshotAndClear() {
        synchronized (lock) {
            QueueSnapshot snapshot = new QueueSnapshot(new LinkedHashSet<>(queuedNotes), new LinkedHashSet<>(deletedNotes), new LinkedHashSet<>(unresolvedCardIds));
            queuedNotes.clear();
            deletedNotes.clear();
            unresolvedCardIds.clear();
            log.debug("Graphify 变更批次取出：notes={}, deletes={}, unresolvedCards={}", snapshot.noteIds().size(), snapshot.deletedNoteIds().size(), snapshot.unresolvedCardIds().size());
            return snapshot;
        }
    }

    void restore(QueueSnapshot snapshot) {
        synchronized (lock) {
            queuedNotes.addAll(snapshot.noteIds());
            deletedNotes.addAll(snapshot.deletedNoteIds());
            unresolvedCardIds.addAll(snapshot.unresolvedCardIds());
            log.warn("Graphify 变更批次恢复入队：notes={}, deletes={}, unresolvedCards={}", snapshot.noteIds().size(), snapshot.deletedNoteIds().size(), snapshot.unresolvedCardIds().size());
        }
    }

    void scheduleIfReady() {
        synchronized (lock) {
            if (ready.getAsBoolean() && !isEmpty()) {
                log.debug("Graphify 索引已就绪，重新安排待处理变更");
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
            log.debug("Graphify 变更队列已清空");
        }
    }

    QueueSnapshot stop() {
        synchronized (lock) {
            stopped = true;
            cancelScheduled();
            log.info("Graphify 变更队列停止：notes={}, deletes={}, unresolvedCards={}", queuedNotes.size(), deletedNotes.size(), unresolvedCardIds.size());
            return new QueueSnapshot(new LinkedHashSet<>(queuedNotes), new LinkedHashSet<>(deletedNotes), new LinkedHashSet<>(unresolvedCardIds));
        }
    }

    int queuedCount() {
        synchronized (lock) {
            return queuedNotes.size() + deletedNotes.size() + unresolvedCardIds.size();
        }
    }

    private void schedule() {
        cancelScheduled();
        log.debug("Graphify 变更批次已安排：delayMs=2000");
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

    record QueueSnapshot(Set<String> noteIds, Set<String> deletedNoteIds, Set<String> unresolvedCardIds) {

        boolean isEmpty() {
            return noteIds.isEmpty() && deletedNoteIds.isEmpty() && unresolvedCardIds.isEmpty();
        }
    }
}
