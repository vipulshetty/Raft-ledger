package com.raftledger.raft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The in-memory Raft log.
 *
 * Index is 1-based (index 0 = "before any entry" sentinel with term 0).
 * This class holds a mutable list used only inside RaftNodeImpl; the simulator
 * and real storage layer each own their own persistence strategy.
 *
 * Determinism rules: no HashMap, no HashSet, no random access by identity hash.
 */
public final class Log {

    private final List<LogEntry> entries = new ArrayList<>();

    // ── Accessors ─────────────────────────────────────────────────────────────

    /** Number of entries (= last valid index). */
    public long length() { return entries.size(); }

    /** Entry at 1-based index, or null if out of range. */
    public LogEntry get(long index) {
        if (index < 1 || index > entries.size()) return null;
        return entries.get((int) index - 1);
    }

    /** The term of the last entry, or 0 if the log is empty. */
    public long lastTerm() {
        return entries.isEmpty() ? 0L : entries.getLast().term();
    }

    /** 1-based index of the last entry, or 0 if empty. */
    public long lastIndex() { return entries.size(); }

    /** Read-only view of entries from startIndex (1-based) to end. */
    public List<LogEntry> entriesFrom(long startIndex) {
        if (startIndex > entries.size()) return List.of();
        return Collections.unmodifiableList(
                entries.subList((int) startIndex - 1, entries.size()));
    }

    /** True when this log is at least as up-to-date as (otherLastTerm, otherLastIndex). */
    public boolean isAtLeastAsUpToDate(long otherLastTerm, long otherLastIndex) {
        if (lastTerm() != otherLastTerm) return lastTerm() > otherLastTerm;
        return lastIndex() >= otherLastIndex;
    }

    // ── Mutations (called only from RaftNodeImpl) ─────────────────────────────

    /** Append a new entry; its index must equal length() + 1. */
    public void append(LogEntry entry) {
        if (entry.index() != entries.size() + 1) {
            throw new IllegalArgumentException(
                    "Expected index " + (entries.size() + 1) + " but got " + entry.index());
        }
        entries.add(entry);
    }

    /**
     * Truncate all entries after (and including) index.
     * Used when a follower receives conflicting entries from a new leader.
     */
    public void truncateFrom(long index) {
        if (index < 1) return;
        entries.subList((int) index - 1, entries.size()).clear();
    }

    /**
     * Replace log contents wholesale (used during Restart from persisted state).
     */
    public void loadFrom(List<LogEntry> persisted) {
        entries.clear();
        entries.addAll(persisted);
    }

    @Override
    public String toString() {
        return "Log[length=" + entries.size() + ", lastTerm=" + lastTerm() + "]";
    }
}
