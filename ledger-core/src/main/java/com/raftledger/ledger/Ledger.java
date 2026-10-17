package com.raftledger.ledger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deterministic, in-memory ledger state machine.
 *
 * <p>The EXTERNAL account is created at construction time. Deposits debit it
 * and credit the requested account, so the total balance remains zero.</p>
 */
public final class Ledger {
    public static final String EXTERNAL_ACCOUNT_ID = "EXTERNAL";

    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, CommandResult> appliedIdempotencyKeys = new LinkedHashMap<>();

    public Ledger() {
        accounts.put(EXTERNAL_ACCOUNT_ID,
                new Account(EXTERNAL_ACCOUNT_ID, "External funding", 0L, true));
    }

    public CommandResult apply(Command command) {
        return switch (command) {
            case Command.CreateAccount create -> createAccount(create);
            case Command.Deposit deposit -> deposit(deposit);
            case Command.Transfer transfer -> transfer(transfer);
        };
    }

    public Account account(String accountId) {
        return accounts.get(accountId);
    }

    public Map<String, Account> accounts() {
        return Collections.unmodifiableMap(accounts);
    }

    public long totalBalance() {
        long total = 0L;
        for (Account account : accounts.values()) {
            total = Math.addExact(total, account.balance());
        }
        return total;
    }

    /**
     * Stable representation hash useful for deterministic replay checks.
     */
    public String stateHash() {
        StringBuilder state = new StringBuilder();
        for (Account account : accounts.values()) {
            state.append(account.accountId()).append('\0')
                    .append(account.displayName()).append('\0')
                    .append(account.balance()).append('\0')
                    .append(account.external()).append('\n');
        }
        state.append("--idempotency--\n");
        for (Map.Entry<String, CommandResult> entry : appliedIdempotencyKeys.entrySet()) {
            state.append(entry.getKey()).append('\0')
                    .append(entry.getValue()).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(state.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >>> 4) & 0xf, 16))
                        .append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    private CommandResult createAccount(Command.CreateAccount command) {
        if (isBlank(command.accountId()) || accounts.containsKey(command.accountId())) {
            return new CommandResult.Rejected("Account already exists or has no id");
        }
        accounts.put(command.accountId(),
                new Account(command.accountId(), command.displayName(), 0L, command.external()));
        return new CommandResult.AccountCreated(command.accountId());
    }

    private CommandResult deposit(Command.Deposit command) {
        CommandResult previous = previousResult(command.idempotencyKey());
        if (previous != null) return previous;
        CommandResult result;
        Account target = accounts.get(command.accountId());
        if (target == null) {
            result = new CommandResult.Rejected("Unknown account: " + command.accountId());
        } else if (target.external()) {
            result = new CommandResult.Rejected("Deposits require a non-external account");
        } else if (command.amountMinorUnits() <= 0) {
            result = new CommandResult.Rejected("Amount must be positive");
        } else {
            long externalBalance = Math.subtractExact(
                    accounts.get(EXTERNAL_ACCOUNT_ID).balance(), command.amountMinorUnits());
            long targetBalance = Math.addExact(target.balance(), command.amountMinorUnits());
            accounts.put(EXTERNAL_ACCOUNT_ID,
                    accounts.get(EXTERNAL_ACCOUNT_ID).withBalance(externalBalance));
            accounts.put(target.accountId(), target.withBalance(targetBalance));
            result = new CommandResult.DepositApplied(target.accountId(), targetBalance);
        }
        return remember(command.idempotencyKey(), result);
    }

    private CommandResult transfer(Command.Transfer command) {
        CommandResult previous = previousResult(command.idempotencyKey());
        if (previous != null) return previous;
        CommandResult result;
        Account from = accounts.get(command.fromAccountId());
        Account to = accounts.get(command.toAccountId());
        if (from == null || to == null) {
            result = new CommandResult.Rejected("Unknown account");
        } else if (command.amountMinorUnits() <= 0) {
            result = new CommandResult.Rejected("Amount must be positive");
        } else if (command.fromAccountId().equals(command.toAccountId())) {
            result = new CommandResult.Rejected("Source and destination must differ");
        } else {
            long sourceBalance = Math.subtractExact(from.balance(), command.amountMinorUnits());
            long destinationBalance = Math.addExact(to.balance(), command.amountMinorUnits());
            if (!from.external() && sourceBalance < 0) {
                result = new CommandResult.Rejected("Insufficient funds");
            } else {
                accounts.put(from.accountId(), from.withBalance(sourceBalance));
                accounts.put(to.accountId(), to.withBalance(destinationBalance));
                result = new CommandResult.TransferApplied(
                        from.accountId(), to.accountId(), command.amountMinorUnits());
            }
        }
        return remember(command.idempotencyKey(), result);
    }

    private CommandResult previousResult(String key) {
        return key == null ? null : appliedIdempotencyKeys.get(key);
    }

    private CommandResult remember(String key, CommandResult result) {
        if (key != null && !key.isBlank()) {
            CommandResult existing = appliedIdempotencyKeys.putIfAbsent(key, result);
            return existing == null ? result : existing;
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
