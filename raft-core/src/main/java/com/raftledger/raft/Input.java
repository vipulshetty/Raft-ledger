package com.raftledger.raft;

/**
 * Every event that can drive a RaftNode forward.
 *
 * The sealed hierarchy + pattern-matching switch lets the compiler verify
 * exhaustiveness — no instanceof chain, no default that silently eats events.
 */
public sealed interface Input
        permits Input.Tick,
                Input.MessageReceived,
                Input.ClientCommand,
                Input.Restart {

    /** A logical heartbeat / election-timeout tick from the virtual or real clock. */
    record Tick(long virtualTimeMs) implements Input {}

    /** A Raft protocol message delivered to this node from another node. */
    record MessageReceived(RaftMessage message) implements Input {}

    /**
     * A client-initiated command (ledger operation) that the leader must replicate.
     * Contains a session id and sequence number for exactly-once semantics.
     */
    record ClientCommand(
            String sessionId,
            long   sequenceNumber,
            byte[] commandBytes      // serialised ledger Command
    ) implements Input {}

    /**
     * A restart signal: volatile state is gone, only what the runtime actually
     * made durable survives. The recovered state arrives here as data — the core
     * reads no storage (see determinism.md).
     *
     * <p>{@code durableLog} is what the write-ahead log still holds after the
     * crash. Passing a snapshot that is older than the node's last in-memory
     * state is exactly how an un-fsynced write is modelled: the term or vote
     * that never reached disk is simply absent, and recovery must not invent it.
     *
     * @param persisted  term and vote as read back from stable storage
     * @param durableLog the log records still present in storage, index 1 upward
     */
    record Restart(PersistentState persisted, java.util.List<LogEntry> durableLog) implements Input {
        public Restart {
            durableLog = java.util.List.copyOf(durableLog);
            if (persisted.logLength() != durableLog.size()) {
                throw new IllegalArgumentException("logLength " + persisted.logLength()
                        + " disagrees with " + durableLog.size() + " durable log records");
            }
        }

        /** A node restarting after a crash that left nothing durable. */
        public static Restart fromEmptyStorage() {
            return new Restart(PersistentState.initial(), java.util.List.of());
        }
    }
}
