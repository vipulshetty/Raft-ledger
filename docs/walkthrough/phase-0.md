# Phase 0 walkthrough

Phase 0 turns the initial core code into a reproducible build. The Maven
Wrapper means a contributor or CI runner can invoke the project without
relying on a globally installed Maven version. CI uses Temurin 21, matching
the source and compiler target.

The ledger tests now state four independent properties: conservation including
the EXTERNAL account, non-negative non-external balances, idempotent transfer
replay, and deterministic state hashes for identical command sequences. The
overflow test uses `Math.addExact`/`Math.subtractExact`, so arithmetic overflow
is observable rather than silently wrapping.

ArchUnit protects the sans-I/O boundary. The canary deliberately introduced
`Instant.now()` and failed the architecture test. A second temporary mutation
removed a transfer debit and failed the ledger properties. Both changes were
reverted after the demonstrations.

The idempotency table is deliberately retained for now because application
does not yet carry a replicated log index. An ADR records that limitation and
the required snapshot-based pruning design for a later phase.

For an interview, the key explanation is that reproducibility is a design
constraint: dependencies, clocks, randomness, persistence, and transport are
kept outside the deterministic state machines, and tests enforce that boundary.
