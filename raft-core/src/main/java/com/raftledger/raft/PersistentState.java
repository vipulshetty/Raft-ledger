package com.raftledger.raft;

/**
 * The durable state a node must persist to stable storage before sending any
 * message whose correctness depends on it.
 *
 * votedFor: -1 means "has not voted this term".
 * logLength: the number of log records the runtime must have made durable for
 *      this record to be recoverable; {@link Input.Restart} rejects a snapshot
 *      whose entry count disagrees. The WAL owns the bytes, this record owns the
 *      term and vote that gate an election.
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
