# ADR-002: Retain idempotency keys until snapshotting exists

## Decision

The ledger retains successful and rejected idempotency results in insertion
order for the lifetime of the in-memory state machine. The current
`ledger-core` API does not receive a replicated log index, so it cannot safely
expire a key based on that index yet.

## Rationale

Idempotency is part of replicated state, not an adapter cache. Expiring by wall
clock would make replicas disagree, and expiring after an arbitrary number of
commands would allow a retry to be applied twice if replicas compacted at
different points. Keeping the table until a snapshot/compaction protocol exists
preserves deterministic replay and exactly-once behavior.

## Consequences and follow-up

The table is currently unbounded and is therefore unsuitable for an
indefinitely running production node. Phase 2/3 must add the replicated log
index to state-machine application and define a snapshot boundary. At that
point, keys older than the agreed snapshot/log-index retention window can be
pruned identically on every node.
