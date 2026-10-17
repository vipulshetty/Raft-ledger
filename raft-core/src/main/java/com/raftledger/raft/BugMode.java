package com.raftledger.raft;

/**
 * Planted defects for the mutation gauntlet.
 *
 * Each enum constant activates exactly one deliberate bug so the simulator
 * can prove it finds every known class of Raft correctness error.
 *
 * TEST SCOPE ONLY — never use in production or the real node-app runtime.
 */
public enum BugMode {

    /** No bug planted: the correct implementation. */
    NONE,

    /**
     * A leader commits entries from earlier terms by replica count.
     * This is the Raft paper's Figure 8 scenario: an old entry can be
     * overwritten by a later leader even though it appeared "committed".
     */
    COMMIT_OLD_TERM_BY_COUNT,

    /**
     * A voter ignores the "candidate log at least as up-to-date" rule.
     * Allows a stale candidate to win and overwrite committed entries.
     */
    NO_LOG_UPTODATE_CHECK,

    /**
     * votedFor is NOT persisted before sending the vote reply.
     * After a crash+restart a node can vote for two different candidates
     * in the same term, breaking Election Safety.
     */
    VOTE_NOT_PERSISTED,

    /**
     * A follower applies a log entry as soon as it is appended (before commit).
     * The entry might be overwritten, causing divergent state machines.
     */
    APPLY_BEFORE_COMMIT,

    /**
     * A duplicate client command (same idempotencyKey) is applied twice.
     * Models a missing exactly-once check in the state machine.
     */
    NON_IDEMPOTENT_RETRY;

    // ── Convenience predicates used in RaftNodeImpl ────────────────────────────

    public boolean commitOldTermByCount() { return this == COMMIT_OLD_TERM_BY_COUNT; }
    public boolean noLogUpToDateCheck()   { return this == NO_LOG_UPTODATE_CHECK; }
    public boolean voteNotPersisted()     { return this == VOTE_NOT_PERSISTED; }
    public boolean applyBeforeCommit()    { return this == APPLY_BEFORE_COMMIT; }
    public boolean nonIdempotentRetry()   { return this == NON_IDEMPOTENT_RETRY; }
}
