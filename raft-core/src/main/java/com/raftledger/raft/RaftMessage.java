package com.raftledger.raft;

/**
 * All wire messages that Raft nodes exchange.
 *
 * Each variant is a record so that equals/hashCode/toString work by value,
 * which is required for deterministic simulation replay.
 */
public sealed interface RaftMessage
        permits RaftMessage.RequestVote,
                RaftMessage.RequestVoteReply,
                RaftMessage.AppendEntries,
                RaftMessage.AppendEntriesReply {

    int fromNodeId();

    // ── Vote ─────────────────────────────────────────────────────────────────

    record RequestVote(
            int  fromNodeId,
            long term,
            long lastLogIndex,
            long lastLogTerm
    ) implements RaftMessage {}

    record RequestVoteReply(
            int     fromNodeId,
            long    term,
            boolean voteGranted
    ) implements RaftMessage {}

    // ── AppendEntries (also serves as heartbeat when entries is empty) ────────

    record AppendEntries(
            int             fromNodeId,
            long            term,
            long            prevLogIndex,
            long            prevLogTerm,
            java.util.List<LogEntry> entries,
            long            leaderCommit
    ) implements RaftMessage {}

    record AppendEntriesReply(
            int     fromNodeId,
            long    term,
            boolean success,
            long    matchIndex,
            // fast catch-up hint: the conflicting term and the earliest index of that term
            long    conflictTerm,
            long    conflictIndex
    ) implements RaftMessage {}
}
