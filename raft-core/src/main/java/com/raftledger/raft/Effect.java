package com.raftledger.raft;

import java.util.List;

/**
 * Every side-effect a RaftNode can declare.
 *
 * The runtime (real app or simulator) executes these IN ORDER.
 * Ordering is load-bearing: Persist must precede any Send that reveals the
 * persisted state to other nodes (see determinism.md).
 */
public sealed interface Effect
        permits Effect.Send,
                Effect.Persist,
                Effect.Apply,
                Effect.ResetElectionTimer,
                Effect.Reply {

    /**
     * Send a Raft protocol message to another node.
     * The runtime resolves the destination id to an actual address.
     */
    record Send(int destinationNodeId, RaftMessage message) implements Effect {}

    /**
     * Persist durable state before releasing any message that depends on it.
     * The runtime must fsync before executing any subsequent Send.
     */
    record Persist(PersistentState state) implements Effect {}

    /**
     * A log entry has been committed: apply it to the state machine.
     * The runtime feeds the byte payload to the ledger's apply() method.
     */
    record Apply(long logIndex, byte[] commandBytes, String sessionId, long sequenceNumber)
            implements Effect {}

    /**
     * Reset the election timeout to a newly drawn random duration.
     * The runtime supplies fresh randomness; the core only declares the intent.
     */
    record ResetElectionTimer() implements Effect {}

    /**
     * Reply to a client whose command has been committed and applied.
     * Carries the raw result bytes from the state machine.
     */
    record Reply(String sessionId, long sequenceNumber, boolean success, byte[] resultBytes)
            implements Effect {}

    // ── convenience factory ───────────────────────────────────────────────────

    /** Broadcast a message to all other nodes (the runtime expands the list). */
    static List<Effect> broadcast(int selfId, int clusterSize, RaftMessage message) {
        return java.util.stream.IntStream.range(0, clusterSize)
                .filter(id -> id != selfId)
                .mapToObj(id -> (Effect) new Send(id, message))
                .toList();
    }
}
