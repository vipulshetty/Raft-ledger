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
     * A restart signal: volatile state is gone, only persisted state survives.
     * Used by the simulator to model a crash-and-recover cycle.
     */
    record Restart() implements Input {}
}
