package com.raftledger.ledger;

/**
 * Every command the ledger state machine understands.
 *
 * Commands are serialised to bytes by the application layer, replicated through
 * Raft, and then applied via {@link Ledger#apply}.
 *
 * Determinism rule: no wall-clock, no randomness inside apply(). Timestamps
 * must be stamped into commands before replication (the leader does this).
 */
public sealed interface Command
        permits Command.CreateAccount,
                Command.Deposit,
                Command.Transfer {

    /**
     * Open a new named account.
     *
     * @param accountId  stable external identifier (e.g. UUID string)
     * @param displayName  human-readable label
     * @param external  if true the account may go negative (e.g. EXTERNAL funding account)
     */
    record CreateAccount(
            String accountId,
            String displayName,
            boolean external
    ) implements Command {}

    /**
     * Move funds from the special EXTERNAL account into a real account.
     * Amount in minor units (e.g. cents). Must be positive.
     */
    record Deposit(
            String accountId,
            long   amountMinorUnits,
            String idempotencyKey,
            long   timestampMs        // stamped by the leader
    ) implements Command {}

    /**
     * Transfer between two ledger accounts.
     *
     * @param idempotencyKey  unique key; duplicate keys are silently ignored
     */
    record Transfer(
            String fromAccountId,
            String toAccountId,
            long   amountMinorUnits,
            String idempotencyKey,
            long   timestampMs
    ) implements Command {}
}
