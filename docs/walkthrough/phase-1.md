# Phase 1 walkthrough

Phase 1 adds a deterministic laboratory without changing the Raft or ledger
state machines. `VirtualClock` orders events by virtual time and then by an
explicit sequence number, so equal-time events are reproducible.

`SimNetwork` models drops, duplicates, delay ranges, and directional links.
Partitions block both directions when requested, while `heal` removes those
links. `SimDisk` separates unsynced writes from durable writes; a crash drops
only the former. All of these are ordinary in-memory structures and use no
wall clock, sockets, files, or threads.

`World` is a deliberately small replicated-counter toy protocol. It uses the
named `L64X128MixRandom` algorithm with an explicit seed, records every
logical operation, and hashes the trace. The broken variant duplicates an
increment and is caught by the replica-equality invariant. Tests also launch
the child protocol in a separate JVM and compare its hash with the parent.

For an interview, explain why the simulator is not a second implementation of
Raft: it is a runtime for deterministic state machines. This makes failures
replayable and lets the same core logic later run against a fault-injecting
network and disk.
