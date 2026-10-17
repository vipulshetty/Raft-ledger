package com.raftledger.raft;

/**
 * The durable state a node must persist to stable storage before sending any
 * message whose correctness depends on it.
 *
 * votedFor: -1 means "has not voted this term".
 * log: the full append-only Raft log (persisted incrementally by the WAL; this
 *      record carries only metadata — the WAL owns the bytes).
 */
public record PersistentState(
        long currentTerm,
        int  votedFor,   // node id, or -1
        int  logLength   // number of entries currently in the log
) {
    public static PersistentState initial() {
        return new PersistentState(0L, -1, 0);
    }
}
