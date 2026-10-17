package com.raftledger.ledger;

/**
 * The result returned to the client after a command is applied.
 */
public sealed interface CommandResult
        permits CommandResult.AccountCreated,
                CommandResult.DepositApplied,
                CommandResult.TransferApplied,
                CommandResult.DuplicateIgnored,
                CommandResult.Rejected {

    record AccountCreated(String accountId) implements CommandResult {}
    record DepositApplied(String accountId, long newBalance) implements CommandResult {}
    record TransferApplied(String fromAccountId, String toAccountId, long amount) implements CommandResult {}
    record DuplicateIgnored(String idempotencyKey) implements CommandResult {}
    record Rejected(String reason) implements CommandResult {}
}
