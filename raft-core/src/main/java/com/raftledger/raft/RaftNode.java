package com.raftledger.raft;

import java.util.List;

/**
 * The RaftNode interface: a pure function from state + input → effects.
 *
 * Implementations must be deterministic: same volatile+persistent state,
 * same input → identical returned effect list, every time.
 * No threads, clocks, sockets or files may be touched inside step().
 */
public interface RaftNode {

    /**
     * Drive the node one step forward.
     *
     * @param input  the event to process
     * @return       effects to execute, in order
     */
    List<Effect> step(Input input);

    // ── Inspection (read-only; used by the invariant checker) ─────────────────

    int     nodeId();
    Role    role();
    long    currentTerm();
    int     votedFor();
    long    commitIndex();
    long    lastApplied();
    Log     log();
    int     currentLeader(); // -1 if unknown
}
