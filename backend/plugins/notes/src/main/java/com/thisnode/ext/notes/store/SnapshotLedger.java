package com.thisnode.ext.notes.store;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 快照台账：{@code .notes/snapshots.json}。
 *
 * <p>快照 zip 本身在 {@code thisnode.snapshot-root}，这里只记"有哪些、多大、覆盖多少篇笔记"。
 * 与分组台账同构：数量少，一个文件装得下，就不必一个快照一份文件。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SnapshotLedger(int v, List<Entry> entries) {

    public static final int SCHEMA = 1;

    public SnapshotLedger {
        v = v <= 0 ? SCHEMA : v;
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public static SnapshotLedger empty() {
        return new SnapshotLedger(SCHEMA, List.of());
    }

    /** 最新的在前。 */
    public List<Entry> sorted() {
        List<Entry> out = new ArrayList<>(entries);
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    public SnapshotLedger with(Entry entry) {
        List<Entry> next = new ArrayList<>();
        boolean replaced = false;
        for (Entry existing : entries) {
            if (existing.id().equals(entry.id())) {
                next.add(entry);
                replaced = true;
            } else {
                next.add(existing);
            }
        }
        if (!replaced) {
            next.add(entry);
        }
        return new SnapshotLedger(v, next);
    }

    public SnapshotLedger without(String id) {
        List<Entry> next = new ArrayList<>();
        for (Entry existing : entries) {
            if (!existing.id().equals(id)) {
                next.add(existing);
            }
        }
        return new SnapshotLedger(v, next);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(int v, String id, String kind, String path, long size, int nodeCount,
                        long createdAt) {

        public Entry {
            v = v <= 0 ? SCHEMA : v;
            kind = kind == null || kind.isBlank() ? "manual" : kind;
        }
    }
}
