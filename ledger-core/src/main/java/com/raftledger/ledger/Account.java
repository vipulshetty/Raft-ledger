package com.raftledger.ledger;

/**
 * A single account in the ledger.
 *
 * balance: in minor units (long). Never double.
 * external: if true the account is allowed to go negative (the funding source).
 */
public record Account(
        String  accountId,
        String  displayName,
        long    balance,
        boolean external
) {
    public Account withBalance(long newBalance) {
        return new Account(accountId, displayName, newBalance, external);
    }
}
