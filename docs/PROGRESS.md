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
