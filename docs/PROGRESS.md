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

RAFT CORRECTNESS 1: Fast catch-up verification and regression tests — GATE: PASS
Commands run:
  `./mvnw -B -o -pl raft-core test -Dtest='RaftNodeCatchUpTest'` -> exit 0, tests: 9/0/0
  Mutation M1 `nextIndex[peer] = aer.conflictIndex()` -> exit 1, tests: 9/1/0; only `knownConflictTermTrim...` failed with `[6L, 1L]` vs expected `[6L, 3L]`
  Mutation M2 (both backtracking assignments removed) -> exit 1, tests: 9/9/0; every test aborted with `delivery budget exhausted`
  Mutation M3 (conflict-term hint ignored, always `nextIndex - 1`) -> exit 1, tests: 9/3/0
  `git diff --stat -- raft-core/src/main/java/com/raftledger/raft/RaftNodeImpl.java` -> empty (main source restored byte-identical after each mutation)
Files added/changed: `raft-core/src/test/java/com/raftledger/raft/RaftCluster.java` (deterministic in-process cluster; logs are produced only by exchanged messages, never hand-seeded), `RaftNodeCatchUpTest.java` (9 behavioural tests). No production code changed.
Verdict: the alleged fast-catch-up defect at `RaftNodeImpl.java:372` is NOT real. `nextIndex[peer] = last + 1` where `last` is the leader's highest index holding `conflictTerm` is exactly the extended-paper rule (conflict term known -> jump one past the leader's last index of that term; unknown -> jump to the follower's reported `conflictIndex`; no hint -> decrement by one). The fix proposed by the earlier audit (jump to the first index of the conflict term) is strictly wrong: it re-sends entries the follower already holds, and cannot advance nextIndex at all when the rejection index is the first index of that term. The prior "livelock" report came from feeding the leader a protocol-impossible reply.
Confirmed gap (next priority, currently pinned by tests but not fixed): a follower whose log is shorter than `prevLogIndex` sends `conflictTerm = 0, conflictIndex = 0` (`RaftNodeImpl.java:287-295`) instead of `lastLogIndex + 1`, so the leader backtracks one index per round trip. Observed `nextIndex` sequence for a 2-entry gap: `[5, 4, 3]`; for a 6-entry gap the leader walked `prev 8 -> 2`. Liveness is not at risk (every rejection strictly decreases `nextIndex`), only O(distance) wasted round trips.
Decisions made (ADRs): none; this increment added tests and evidence only.

NEXT INCREMENTS (Raft correctness)
Four separate changes, each independently reviewable and each gated by `./mvnw -B -o clean verify`. Ordered by risk. Do not bundle them: 1 and 2 both touch the restart path but 1 is core-only while 2 is sim-only, and 3 depends on 2 existing.

INCREMENT 1 — Durable state restore on restart (raft-core only)
  Verified today: `Effect.Persist` is emitted in the correct positions (self-vote `RaftNodeImpl.java:151-153`, term bump 176-181, vote grant 207-211, no-op append 250-252, replicated entries 316-318) and always precedes the `Send` that reveals it. `PersistentState` is metadata only: `(currentTerm, votedFor, logLength)`; no log bytes are carried. `Log.loadFrom(List<LogEntry>)` (`Log.java:80`) has zero callers. `onRestart()` (`RaftNodeImpl.java:420-431`) clears only volatile state and its comment asserts "currentTerm, votedFor and log already reloaded by the runtime" — no such runtime path exists, so a crash-and-recover cycle today restores nothing.
  Scope: give the core a real recovery contract. Extend the durable record (or add `RaftNode.recover(PersistentState, List<LogEntry>)` supplied by the runtime) so `currentTerm`, `votedFor` and log entries actually come back; keep `step(Input.Restart)` as the trigger. Do not add file I/O to the core — determinism.md forbids it; the state arrives as an Input.
  Tests: restart restores term and `votedFor` so a node cannot vote twice in one term across a crash; a node whose log was truncated by a rejected leader comes back with the pre-crash log and re-accepts the correct leader; `BugMode.VOTE_NOT_PERSISTED` is detected by a crash-then-double-vote test (its javadoc at `BugMode.java:29-34` currently describes exactly this scenario but no test exercises the restart half of it).
  Why separate: changes only raft-core types and their unit tests; no simulator or ledger involvement.

INCREMENT 2 — Effect executor that wires real RaftNodes into the simulator (sim only)
  Verified today: `sim/src/main/java/com/raftledger/sim/` contains `VirtualClock`, `SimNetwork`, `SimDisk`, `Scenario`, `World`. `World.run` is a toy: three `int[]` counters incremented by seeded randomness, with no `RaftNodeImpl` and no `Effect` execution. `Scenario` (including `Fault.Type` DROP/DUPLICATE/PARTITION/HEAL/CRASH/PAUSE) has zero consumers. `SimDisk` is exercised only by `SimulatorTest.java:70,82`. `raft-core/src/test/.../RaftCluster.java` already proves the wiring shape (FIFO delivery, per-link cut, entry blackhole) but deliberately ignores `Effect.Persist` — it has no disk, so persist-before-send is unenforced there — and it is test-scoped in raft-core, so `sim` cannot import it without a module dependency.
  Scope: a production `EffectExecutor` in `sim` that drives N `RaftNodeImpl`s over `SimNetwork` + `VirtualClock` + `SimDisk`, honouring the ordering rule in `Effect.java:8-10` — fsync before executing any later `Send`. Crash is `SimDisk` flush-then-rebuild plus `Input.Restart`, fed from the durable record created by Increment 1.
  Tests: executor determinism (same seed -> same `traceHash` across JVMs, reusing the existing `SimulatorChildMain` pattern); persist-before-send is actually enforced, e.g. an injected crash between `Persist` and `Send` cannot leak an un-persisted vote.
  Why separate: pure `sim` module work, no raft-core API change; it is the prerequisite for Increment 3.

INCREMENT 3 — Simulator-driven elections, partitions, crashes, recovery (sim + test scenarios, depends on 2)
  Verified today: fault behaviour exists only as hand-built choreographies — single-node `step()` sequences in `RaftNodeElectionTest` and `RaftNodeReplicationTest`, and the message-driven `RaftCluster` scenarios in `RaftNodeCatchUpTest`. Nothing runs a scheduled fault list.
  Scope: make `Scenario.Fault` a real input to `World`-successor; replace the counter world with the Raft world. Run seeded sweeps over cluster size, partition shape, crash points and message-delay distributions.
  Invariants to assert every step: Election Safety (at most one leader per term), State Machine Safety (same index applied -> same command in every node), Log Matching, and only-leader-commits / current-term-only commitment. Then run the whole `BugMode` gauntlet through the simulator and record which planted defect each invariant actually kills — the audit already showed one bug mode (`NON_IDEMPOTENT_RETRY`, `BugMode.java:46,54`) has zero callers, so an enum constant is not evidence of coverage.
  Why separate: this is scenario + oracle work on top of a stable executor; it should not be reviewed together with the executor's construction.

INCREMENT 4 — Replicated client-command deduplication and retry (raft-core + ledger-core)
  Verified today: `Input.ClientCommand` carries `(sessionId, sequenceNumber, commandBytes)` and `Effect.Apply` echoes both, but deduplication is not replicated — `BugMode.NON_IDEMPOTENT_RETRY` and `BugMode.nonIdempotentRetry()` have no call sites, so no duplicate-command behaviour exists to test. ADR-002 records that ledger-side idempotency results cannot yet be pruned because application has no replicated log-index input.
  Scope: decide the dedup key (session window vs. per-command log entry), replicate it, and make the leader's client retry path observable — including the "committed but the reply was lost" case where a retry must return the stored result rather than re-execute.
  Tests: same `(sessionId, sequenceNumber)` replayed across a leader change applies once and replies identically both times; a retry that arrives before commit is not double-applied; then activate `NON_IDEMPOTENT_RETRY` and prove a test fails, or delete the constant.
  Why separate: it can change the ledger state-machine interface, which neither Increment 1-3 nor the existing replication tests depend on.
