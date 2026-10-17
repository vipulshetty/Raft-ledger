package com.raftledger.sim;

import java.util.List;

public record Scenario(List<Fault> faults, List<ClientOperation> operations) {
    public Scenario {
        faults = List.copyOf(faults);
        operations = List.copyOf(operations);
    }

    public record Fault(long at, Type type, int from, int to) {}

    public enum Type {
        DROP,
        DUPLICATE,
        PARTITION,
        HEAL,
        CRASH,
        PAUSE
    }

    public record ClientOperation(long at, int amount) {}
}
