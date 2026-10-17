package com.raftledger.ledger;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTest {
    @Property
    void sumOfAllBalancesIncludingExternalIsAlwaysZero(
            @ForAll @IntRange(min = 1, max = 1_000) int amount) {
        Ledger ledger = fundedLedger();
        Command.Transfer transfer = new Command.Transfer(
                "alice", "bob", amount, "transfer-1", 0L);

        ledger.apply(transfer);

        assertThat(ledger.totalBalance()).isZero();
    }

    @Property
    void noNonExternalBalanceIsEverNegative(
            @ForAll @IntRange(min = 1, max = 1_000) int amount) {
        Ledger ledger = fundedLedger();
        ledger.apply(new Command.Transfer("alice", "bob", amount, "transfer-1", 0L));

        assertThat(ledger.account("alice").balance()).isGreaterThanOrEqualTo(0L);
        assertThat(ledger.account("bob").balance()).isGreaterThanOrEqualTo(0L);
    }

    @Property
    void replayingTransferWithSameIdempotencyKeyChangesNothingAndReturnsSameResult(
            @ForAll @IntRange(min = 1, max = 1_000) int amount) {
        Ledger ledger = fundedLedger();
        Command.Transfer transfer = new Command.Transfer(
                "alice", "bob", amount, "transfer-1", 0L);

        CommandResult first = ledger.apply(transfer);
        String stateAfterFirst = ledger.stateHash();
        CommandResult replay = ledger.apply(transfer);

        assertThat(replay).isEqualTo(first);
        assertThat(ledger.stateHash()).isEqualTo(stateAfterFirst);
    }

    @Property
    void identicalCommandSequencesProduceIdenticalStateHashes(
            @ForAll @IntRange(min = 1, max = 1_000) int amount) {
        Ledger first = fundedLedger();
        Ledger second = fundedLedger();
        Command.Transfer transfer = new Command.Transfer(
                "alice", "bob", amount, "same-sequence", 0L);

        first.apply(transfer);
        second.apply(transfer);

        assertThat(first.stateHash()).isEqualTo(second.stateHash());
    }

    @Property
    void overflowIsRejectedWithoutChangingState() {
        Ledger ledger = new Ledger();
        ledger.apply(new Command.CreateAccount("alice", "Alice", false));
        ledger.apply(new Command.CreateAccount("bob", "Bob", false));
        CommandResult result = ledger.apply(new Command.Deposit(
                "alice", Long.MAX_VALUE, "overflow", 0L));

        assertThat(result).isEqualTo(new CommandResult.DepositApplied("alice", Long.MAX_VALUE));
        assertThat(ledger.totalBalance()).isZero();
        assertThatThrownBy(() -> ledger.apply(new Command.Deposit(
                "alice", 1L, "overflow-2", 0L)))
                .isInstanceOf(ArithmeticException.class);
        assertThat(ledger.totalBalance()).isZero();
    }

    private static Ledger fundedLedger() {
        Ledger ledger = new Ledger();
        ledger.apply(new Command.CreateAccount("alice", "Alice", false));
        ledger.apply(new Command.CreateAccount("bob", "Bob", false));
        ledger.apply(new Command.Deposit("alice", 10_000L, "funding", 0L));
        return ledger;
    }
}
