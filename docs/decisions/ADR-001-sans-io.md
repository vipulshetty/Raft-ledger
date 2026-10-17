# ADR-001: Keep the core sans-I/O

## Decision

`raft-core`, `ledger-core`, and the deterministic parts of `sim` perform no
direct I/O, clock reads, thread management, or uncontrolled random generation.
They receive data as inputs and return data and effects as outputs.

## Rationale

This makes a node's behavior a function of its prior state and input sequence.
The production adapter can execute effects using HTTP, files, timers, and
threads, while the simulator can execute those same effects with a virtual
clock, fake network, and fake disk. Both runtimes consequently exercise the
same consensus code. Replay and shrinking a failure do not depend on machine
timing or external services.

## Consequences

The boundary is explicit: persistence, transport, scheduling, and serialization
belong to adapters. Time-sensitive behavior is represented by `Tick` inputs,
and leader-generated timestamps are fields of replicated commands. This adds
some adapter code, but prevents hidden nondeterminism from entering the
correctness-critical state machines.
