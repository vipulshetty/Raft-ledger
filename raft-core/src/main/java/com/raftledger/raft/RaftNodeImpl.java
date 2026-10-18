package com.raftledger.raft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Complete Raft consensus implementation.
 *
 * <ul>
 *   <li>Leader election with randomised timeouts (timeout drawn by the runtime; we
 *       only track whether the timer has expired).</li>
 *   <li>Log replication with prevLogIndex/prevLogTerm consistency check and
 *       conflict-term fast catch-up.</li>
 *   <li>Safety: a leader commits entries only from its own term by replica count;
 *       earlier-term entries commit indirectly.</li>
 *   <li>Exactly-once semantics: client session table lives inside the state machine
 *       (i.e., in ledger-core), but we track which (sessionId, seq) are pending here
 *       to route replies.</li>
 *   <li>No-op entry on leader promotion (fixes the Figure 8 / term commitment bug).</li>
 * </ul>
 *
 * DETERMINISM: this class touches NO clock, socket, file or thread.
 * The Tick input carries virtualTimeMs; everything time-driven comes through inputs.
 */
public final class RaftNodeImpl implements RaftNode {

    // ── Configuration ─────────────────────────────────────────────────────────

    private final int nodeId;
    private final int clusterSize;
    private final BugMode bugMode;           // planted bugs for the gauntlet

    // Election and heartbeat timeouts (in ms of virtual time).
    // Set by the runtime; the node only observes elapsed time via Tick.
    private int electionTimeoutMs  = 300;
    private int heartbeatIntervalMs = 75;

    // ── Persistent state (must be Persist-ed before any dependent Send) ────────

    private long currentTerm = 0L;
    private int  votedFor    = -1;
    private final Log log    = new Log();

    // ── Volatile leader state ─────────────────────────────────────────────────

    // nextIndex[i] = next log index to send to follower i
    private final long[] nextIndex;
    // matchIndex[i] = highest log index known replicated on follower i
    private final long[] matchIndex;

    // ── Volatile common state ─────────────────────────────────────────────────

    private Role role          = Role.FOLLOWER;
    private long commitIndex   = 0L;
    private long lastApplied   = 0L;
    private int  currentLeader = -1;

    // Election tracking
    // Distinct peers whose RequestVoteReply granted us a vote this term, plus ourselves.
    // Set semantics matter: a duplicated reply from one peer must not count twice,
    // otherwise a candidate can claim a majority from a single voter and elect two leaders.
    private final Set<Integer> grantedVotes = new LinkedHashSet<>();
    private long lastHeartbeatMs = 0L;   // virtual time of last AppendEntries received
    private long currentVirtualTimeMs = 0L;

    // Pending client commands: sessionId+seq → entry log index (for reply routing)
    // LinkedHashMap for deterministic iteration order
    private final Map<String, Long> pendingReplies = new LinkedHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public RaftNodeImpl(int nodeId, int clusterSize) {
        this(nodeId, clusterSize, BugMode.NONE);
    }

    public RaftNodeImpl(int nodeId, int clusterSize, BugMode bugMode) {
        this.nodeId      = nodeId;
        this.clusterSize = clusterSize;
        this.bugMode     = bugMode;
        this.nextIndex   = new long[clusterSize];
        this.matchIndex  = new long[clusterSize];
        java.util.Arrays.fill(nextIndex, 1L);
    }

    // ── RaftNode interface ────────────────────────────────────────────────────

    @Override public int    nodeId()        { return nodeId; }
    @Override public Role   role()          { return role; }
    @Override public long   currentTerm()   { return currentTerm; }
    @Override public int    votedFor()      { return votedFor; }
    @Override public long   commitIndex()   { return commitIndex; }
    @Override public long   lastApplied()   { return lastApplied; }
    @Override public Log    log()           { return log; }
    @Override public int    currentLeader() { return currentLeader; }

    // ── Main dispatch ─────────────────────────────────────────────────────────

    @Override
    public List<Effect> step(Input input) {
        return switch (input) {
            case Input.Tick t             -> onTick(t);
            case Input.MessageReceived mr -> onMessage(mr.message());
            case Input.ClientCommand cc   -> onClientCommand(cc);
            case Input.Restart r          -> onRestart(r);
        };
    }

    // ── Tick ─────────────────────────────────────────────────────────────────

    private List<Effect> onTick(Input.Tick tick) {
        currentVirtualTimeMs = tick.virtualTimeMs();
        List<Effect> effects = new ArrayList<>();

        if (role == Role.LEADER) {
            // Heartbeat: send AppendEntries to all followers
            if (currentVirtualTimeMs - lastHeartbeatMs >= heartbeatIntervalMs) {
                lastHeartbeatMs = currentVirtualTimeMs;
                for (int i = 0; i < clusterSize; i++) {
                    if (i == nodeId) continue;
                    effects.addAll(sendAppendEntries(i));
                }
            }
        } else {
            // Follower/Candidate: check election timeout
            if (currentVirtualTimeMs - lastHeartbeatMs >= electionTimeoutMs) {
                effects.addAll(startElection());
            }
        }

        effects.addAll(maybeApply());
        return effects;
    }

    // ── Election ─────────────────────────────────────────────────────────────

    private List<Effect> startElection() {
        List<Effect> effects = new ArrayList<>();
        currentTerm++;
        role          = Role.CANDIDATE;
        votedFor      = nodeId;
        grantedVotes.clear();
        grantedVotes.add(nodeId);   // vote for self
        currentLeader = -1;
        lastHeartbeatMs = currentVirtualTimeMs;  // reset timer

        // Persist term and vote BEFORE sending any vote request
        if (!bugMode.voteNotPersisted()) {
            effects.add(new Effect.Persist(persistentState()));
        }
        effects.add(new Effect.ResetElectionTimer());

        var req = new RaftMessage.RequestVote(nodeId, currentTerm, log.lastIndex(), log.lastTerm());
        for (int i = 0; i < clusterSize; i++) {
            if (i != nodeId) effects.add(new Effect.Send(i, req));
        }
        return effects;
    }

    // ── Message routing ───────────────────────────────────────────────────────

    private List<Effect> onMessage(RaftMessage msg) {
        List<Effect> effects = new ArrayList<>();

        // All messages: if we see a higher term, revert to follower
        long msgTerm = switch (msg) {
            case RaftMessage.RequestVote rv           -> rv.term();
            case RaftMessage.RequestVoteReply rvr     -> rvr.term();
            case RaftMessage.AppendEntries ae         -> ae.term();
            case RaftMessage.AppendEntriesReply aer   -> aer.term();
        };

        if (msgTerm > currentTerm) {
            currentTerm   = msgTerm;
            votedFor      = -1;
            role          = Role.FOLLOWER;
            currentLeader = -1;
            effects.add(new Effect.Persist(persistentState()));
        }

        effects.addAll(switch (msg) {
            case RaftMessage.RequestVote rv           -> onRequestVote(rv);
            case RaftMessage.RequestVoteReply rvr     -> onRequestVoteReply(rvr);
            case RaftMessage.AppendEntries ae         -> onAppendEntries(ae);
            case RaftMessage.AppendEntriesReply aer   -> onAppendEntriesReply(aer);
        });

        effects.addAll(maybeApply());
        return effects;
    }

    // ── RequestVote ───────────────────────────────────────────────────────────

    private List<Effect> onRequestVote(RaftMessage.RequestVote rv) {
        List<Effect> effects = new ArrayList<>();

        boolean logUpToDate = bugMode.noLogUpToDateCheck()
                || log.candidateIsAtLeastAsUpToDate(rv.lastLogTerm(), rv.lastLogIndex());

        boolean canVote = (votedFor == -1 || votedFor == rv.fromNodeId())
                && rv.term() >= currentTerm
                && logUpToDate;

        if (canVote) {
            votedFor = rv.fromNodeId();
            lastHeartbeatMs = currentVirtualTimeMs;  // suppress spurious election
            effects.add(new Effect.Persist(persistentState()));
        }

        effects.add(new Effect.Send(rv.fromNodeId(),
                new RaftMessage.RequestVoteReply(nodeId, currentTerm, canVote)));
        return effects;
    }

    // ── RequestVoteReply ──────────────────────────────────────────────────────

    private List<Effect> onRequestVoteReply(RaftMessage.RequestVoteReply rvr) {
        if (role != Role.CANDIDATE || rvr.term() != currentTerm) return List.of();

        List<Effect> effects = new ArrayList<>();
        if (rvr.voteGranted() && grantedVotes.add(rvr.fromNodeId())) {
            if (grantedVotes.size() >= majority()) {
                effects.addAll(becomeLeader());
            }
        }
        return effects;
    }

    // ── Become leader ─────────────────────────────────────────────────────────

    private List<Effect> becomeLeader() {
        role          = Role.LEADER;
        currentLeader = nodeId;
        lastHeartbeatMs = currentVirtualTimeMs;

        // Reinitialise volatile leader state
        long nextIdx = log.lastIndex() + 1;
        for (int i = 0; i < clusterSize; i++) {
            nextIndex[i]  = nextIdx;
            matchIndex[i] = 0L;
        }
        matchIndex[nodeId] = log.lastIndex();

        List<Effect> effects = new ArrayList<>();

        // Append a no-op so earlier-term entries can commit indirectly (fig-8 fix)
        LogEntry noOp = LogEntry.noOp(nextIdx, currentTerm);
        log.append(noOp);
        effects.add(new Effect.Persist(persistentState()));

        // Send initial heartbeat immediately
        for (int i = 0; i < clusterSize; i++) {
            if (i != nodeId) effects.addAll(sendAppendEntries(i));
        }
        return effects;
    }

    // ── AppendEntries ─────────────────────────────────────────────────────────

    private List<Effect> onAppendEntries(RaftMessage.AppendEntries ae) {
        List<Effect> effects = new ArrayList<>();

        // Reject stale leader
        if (ae.term() < currentTerm) {
            effects.add(new Effect.Send(ae.fromNodeId(),
                    new RaftMessage.AppendEntriesReply(nodeId, currentTerm, false, 0L, 0L, 0L)));
            return effects;
        }

        // Valid leader — reset heartbeat timer
        currentLeader   = ae.fromNodeId();
        role            = Role.FOLLOWER;
        lastHeartbeatMs = currentVirtualTimeMs;

        // Consistency check
        boolean prevOk = ae.prevLogIndex() == 0
                || (log.get(ae.prevLogIndex()) != null
                    && log.get(ae.prevLogIndex()).term() == ae.prevLogTerm());

        if (!prevOk) {
            // Fast catch-up: find conflict term info
            long conflictTerm  = 0L;
            long conflictIndex = 0L;
            if (ae.prevLogIndex() <= log.lastIndex()) {
                conflictTerm  = log.get(ae.prevLogIndex()).term();
                // Earliest index of that term
                conflictIndex = ae.prevLogIndex();
                while (conflictIndex > 1
                        && log.get(conflictIndex - 1).term() == conflictTerm) {
                    conflictIndex--;
                }
            }
            effects.add(new Effect.Send(ae.fromNodeId(),
                    new RaftMessage.AppendEntriesReply(
                            nodeId, currentTerm, false, 0L, conflictTerm, conflictIndex)));
            return effects;
        }

        // Append new entries, truncating any conflicting tail
        long insertIdx = ae.prevLogIndex() + 1;
        for (LogEntry incoming : ae.entries()) {
            LogEntry existing = log.get(insertIdx);
            if (existing == null) {
                log.append(incoming);
            } else if (existing.term() != incoming.term()) {
                log.truncateFrom(insertIdx);
                log.append(incoming);
            }
            // else: identical — idempotent, no-op
            insertIdx++;
        }

        if (!ae.entries().isEmpty()) {
            effects.add(new Effect.Persist(persistentState()));
        }

        // Advance commit index
        if (ae.leaderCommit() > commitIndex) {
            commitIndex = Math.min(ae.leaderCommit(), log.lastIndex());
        }

        effects.add(new Effect.Send(ae.fromNodeId(),
                new RaftMessage.AppendEntriesReply(
                        nodeId, currentTerm, true, log.lastIndex(), 0L, 0L)));
        return effects;
    }

    // ── AppendEntriesReply ────────────────────────────────────────────────────

    private List<Effect> onAppendEntriesReply(RaftMessage.AppendEntriesReply aer) {
        if (role != Role.LEADER || aer.term() != currentTerm) return List.of();

        List<Effect> effects = new ArrayList<>();
        int peer = aer.fromNodeId();

        if (aer.success()) {
            matchIndex[peer] = Math.max(matchIndex[peer], aer.matchIndex());
            nextIndex[peer]  = matchIndex[peer] + 1;
            matchIndex[nodeId] = log.lastIndex();

            // Try to advance commit index
            // Safety: only commit entries from the CURRENT term by replica count
            // Earlier-term entries commit indirectly when a new current-term entry commits
            for (long n = log.lastIndex(); n > commitIndex; n--) {
                LogEntry e = log.get(n);
                if (e == null) continue;

                boolean fromCurrentTerm = e.term() == currentTerm;
                // BUG: COMMIT_OLD_TERM_BY_COUNT — drops the term check
                if (!fromCurrentTerm && !bugMode.commitOldTermByCount()) continue;

                long replicated = 1L; // self
                for (int i = 0; i < clusterSize; i++) {
                    if (i != nodeId && matchIndex[i] >= n) replicated++;
                }
                if (replicated >= majority()) {
                    commitIndex = n;
                    break;
                }
            }
        } else {
            // Fast catch-up
            if (aer.conflictTerm() > 0) {
                // Find last index of conflictTerm in our log
                long last = -1L;
                for (long i = log.lastIndex(); i >= 1; i--) {
                    if (log.get(i).term() == aer.conflictTerm()) { last = i; break; }
                }
                nextIndex[peer] = last >= 0 ? last + 1 : aer.conflictIndex();
            } else {
                nextIndex[peer] = Math.max(1L, nextIndex[peer] - 1);
            }
            effects.addAll(sendAppendEntries(peer));
        }

        return effects;
    }

    // ── Client command ────────────────────────────────────────────────────────

    private List<Effect> onClientCommand(Input.ClientCommand cc) {
        List<Effect> effects = new ArrayList<>();

        if (role != Role.LEADER) {
            // Tell client to redirect — include leader hint
            effects.add(new Effect.Reply(cc.sessionId(), cc.sequenceNumber(), false,
                    encodeLeaderHint()));
            return effects;
        }

        long newIndex = log.lastIndex() + 1;
        LogEntry entry = new LogEntry(newIndex, currentTerm,
                cc.commandBytes(), cc.sessionId(), cc.sequenceNumber());

        // BUG: APPLY_BEFORE_COMMIT — apply immediately instead of waiting for commit
        if (bugMode.applyBeforeCommit()) {
            effects.add(new Effect.Apply(newIndex, cc.commandBytes(),
                    cc.sessionId(), cc.sequenceNumber()));
        }

        log.append(entry);
        matchIndex[nodeId] = newIndex;
        effects.add(new Effect.Persist(persistentState()));

        String pendingKey = pendingKey(cc.sessionId(), cc.sequenceNumber());
        pendingReplies.put(pendingKey, newIndex);

        // Replicate immediately
        for (int i = 0; i < clusterSize; i++) {
            if (i != nodeId) effects.addAll(sendAppendEntries(i));
        }
        return effects;
    }

    // ── Restart ───────────────────────────────────────────────────────────────

    /**
     * Recovery. Everything the runtime did not make durable is gone: the term, the
     * vote and the log are exactly what the supplied snapshot holds, never what this
     * object happened to be holding when it died.
     *
     * <p>commitIndex and lastApplied are volatile per the paper, so they restart at
     * zero and are re-learned from the leader's heartbeats. Entries therefore reach
     * {@code Apply} a second time after recovery; the state machine is what makes
     * that harmless, by returning the stored result for a repeated idempotency key.
     */
    private List<Effect> onRestart(Input.Restart restart) {
        currentTerm = restart.persisted().currentTerm();
        votedFor    = restart.persisted().votedFor();
        log.loadFrom(restart.durableLog());

        role            = Role.FOLLOWER;
        currentLeader   = -1;
        commitIndex     = 0L;
        lastApplied     = 0L;
        grantedVotes.clear();
        lastHeartbeatMs = currentVirtualTimeMs;
        pendingReplies.clear();
        java.util.Arrays.fill(nextIndex, 1L);
        java.util.Arrays.fill(matchIndex, 0L);
        return List.of(new Effect.ResetElectionTimer());
    }

    // ── Apply committed entries ───────────────────────────────────────────────

    private List<Effect> maybeApply() {
        List<Effect> effects = new ArrayList<>();
        while (lastApplied < commitIndex) {
            lastApplied++;
            LogEntry e = log.get(lastApplied);
            if (e == null || e.isNoOp()) continue;

            effects.add(new Effect.Apply(lastApplied, e.command(), e.sessionId(), e.sequenceNumber()));

            // If we're the leader, also send Reply to the client
            if (role == Role.LEADER) {
                String key = pendingKey(e.sessionId(), e.sequenceNumber());
                if (pendingReplies.remove(key) != null) {
                    effects.add(new Effect.Reply(e.sessionId(), e.sequenceNumber(), true, new byte[0]));
                }
            }
        }
        return effects;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Effect> sendAppendEntries(int peer) {
        long prev = nextIndex[peer] - 1;
        long prevTerm = prev == 0 ? 0L : (log.get(prev) != null ? log.get(prev).term() : 0L);
        List<LogEntry> entries = log.entriesFrom(nextIndex[peer]);

        return List.of(new Effect.Send(peer,
                new RaftMessage.AppendEntries(
                        nodeId, currentTerm, prev, prevTerm, entries, commitIndex)));
    }

    private int majority() { return clusterSize / 2 + 1; }

    private PersistentState persistentState() {
        return new PersistentState(currentTerm, votedFor, (int) log.lastIndex());
    }

    private static String pendingKey(String session, long seq) {
        return session + ":" + seq;
    }

    private byte[] encodeLeaderHint() {
        // Simple 4-byte int encoding; the client decodes it to find the leader.
        int leader = currentLeader;
        return new byte[]{
            (byte)(leader >> 24), (byte)(leader >> 16), (byte)(leader >> 8), (byte)leader
        };
    }

    /** Expose timeout setters so the runtime / simulator can configure them. */
    public void setElectionTimeoutMs(int ms)  { this.electionTimeoutMs  = ms; }
    public void setHeartbeatIntervalMs(int ms) { this.heartbeatIntervalMs = ms; }

    public int electionTimeoutMs()  { return electionTimeoutMs; }
    public int heartbeatIntervalMs() { return heartbeatIntervalMs; }
}
