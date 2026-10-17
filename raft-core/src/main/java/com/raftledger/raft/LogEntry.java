package com.raftledger.raft;

/**
 * A single entry in the Raft replicated log.
 *
 * index: 1-based log position (0 is a sentinel "before any entry").
 * term:  the leader's term when this entry was created.
 * command: serialised ledger Command bytes (or empty for a no-op).
 * sessionId / sequenceNumber: for exactly-once reply tracking.
 */
public record LogEntry(
        long   index,
        long   term,
        byte[] command,
        String sessionId,
        long   sequenceNumber
) {
    /** Construct a leader no-op entry. */
    public static LogEntry noOp(long index, long term) {
        return new LogEntry(index, term, new byte[0], "", -1L);
    }

    public boolean isNoOp() {
        return command.length == 0;
    }
}
