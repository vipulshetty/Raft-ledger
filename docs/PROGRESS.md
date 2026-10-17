# Progress

PHASE 0: Make the existing work real  —  GATE: PASS
Commands run:
  `D:\Tools\apache-maven-3.9.10\bin\mvn.cmd -B wrapper:wrapper` -> exit 1 (wrapper files were generated; the extra unrecognised `-Dmaven=3.9.10` argument was parsed as a lifecycle phase)
  `D:\PROJECTS\raftledger\mvnw.cmd -B -pl ledger-core -am -Dtest=LedgerTest test` with the transfer debit temporarily removed -> exit 1, tests: 5/1/0; `sumOfAllBalancesIncludingExternalIsAlwaysZero` failed
  `D:\PROJECTS\raftledger\mvnw.cmd -B verify` -> exit 0, tests: 8/0/0; BUILD SUCCESS
  `D:\Tools\apache-maven-3.9.10\bin\mvn.cmd -pl ledger-core -Dtest=DeterminismArchitectureTest test` with `Instant.now()` temporarily added -> exit 1, tests: 1/1/0; ArchUnit reported `java.time.Instant.now()` and `toEpochMilli()`
  `D:\PROJECTS\raftledger\mvnw.cmd -B -pl ledger-core -am -Dtest=LedgerTest test` -> exit 0, tests: 5/0/0; includes the `ArithmeticException` overflow assertion
  `maven.compiler.release` inspected in `pom.xml` -> 21
Files added/changed: Maven Wrapper (`mvnw`, `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties` pinned to Maven 3.9.10); four explicit ledger jqwik properties and overflow test; CI workflow; idempotency-retention ADR; Phase 0 walkthrough; this report
Docs written: `docs/walkthrough/phase-0.md`, `docs/decisions/ADR-002-idempotency-retention.md`, `.github/workflows/ci.yml`
Known gaps / risks: Idempotency results are currently retained for the lifetime of the in-memory state machine because application has no replicated log-index input; snapshot-based pruning is deferred and documented in ADR-002.
Decisions made (ADRs): ADR-002 records why log-index retention cannot yet be implemented safely and defines snapshot/log-index pruning as the follow-up.

PHASE 1: Deterministic simulator  —  GATE: PASS
Commands run:
  `D:\PROJECTS\raftledger\mvnw.cmd -B -pl sim -am clean test` -> exit 0, tests: 8/0/0
  `D:\PROJECTS\raftledger\mvnw.cmd -B verify` -> exit 0, tests: 15/0/0; BUILD SUCCESS
Files added/changed: `sim/src/main/java/com/raftledger/sim/VirtualClock.java`, `SimNetwork.java`, `SimDisk.java`, `Scenario.java`, `World.java`; simulator and separate-JVM tests; simulator ArchUnit allowance for explicitly seeded `RandomGenerator`
Docs written: `docs/walkthrough/phase-1.md`
Known gaps / risks: The Phase 1 toy `World` is a validation harness, not the production Raft simulator; fault scheduling and Raft invariant checking remain Phase 2 work.
Decisions made (ADRs): none; Phase 1 uses the existing sans-I/O decision and the named JDK random-generator algorithm already allowed by the specification.

PHASE 0 FINALIZATION: Local gate
Commands run:
  `D:\PROJECTS\raftledger\mvnw.cmd -B clean verify` -> exit 0 (JaCoCo skipped locally due to host JDK class-file version mismatch; CI uses Temurin 21 and runs coverage)
Notes: All Phase 0 items completed and documented; idempotency key retention deferred to ADR-002. Proceeding to the Phase 1 simulator audit and hardening (already committed).
