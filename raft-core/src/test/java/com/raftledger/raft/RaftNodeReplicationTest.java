package com.raftledger.raft;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.raftledger.raft.RaftEffects.applies;
import static com.raftledger.raft.RaftEffects.firstPersistIndex;
import static com.raftledger.raft.RaftEffects.firstSendIndex;
import static com.raftledger.raft.RaftEffects.replies;
import static com.raftledger.raft.RaftEffects.sends;
import static com.raftledger.raft.RaftNodeElectionTest.electToLeader;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for AppendEntries consistency, replication, commitment
 * rules, state-machine application and client-reply routing in {@link RaftNodeImpl}.
 *
 * Mutation-detection tests additionally run the same scenario under the
 * relevant {@link BugMode} to prove the suite actually detects the planted bug.
 */
class RaftNodeReplicationTest {

    private static final byte[] CMD = "transfer".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CMD_B = "transfer-b".getBytes(StandardCharsets.UTF_8);

    // ── AppendEntries consistency check ───────────────────────────────────────

    @Test
    void followerRejectsAppendEntriesWithPrevLogMismatch() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 1L, 1L, 9L, List.of(), 0L)));

        assertThat(node.log().lastIndex()).isZero();
        RaftMessage.AppendEntriesReply reply = soleAeReply(effects);
        assertThat(reply.success()).isFalse();
        assertThat(reply.term()).isEqualTo(1L);
        // Log shorter than prevLogIndex: no conflict-term hint available.
        assertThat(reply.conflictTerm()).isZero();
        assertThat(reply.conflictIndex()).isZero();
    }

    @Test
    void followerRejectsAppendEntriesWithWrongPrevLogTermAndReportsConflictTermStart() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        appendTermOneEntry(node);   // log: [ (1, term 1) ]

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 1L, 1L, 5L, List.of(), 0L)));

        RaftMessage.AppendEntriesReply reply = soleAeReply(effects);
        assertThat(reply.success()).isFalse();
        assertThat(reply.conflictTerm()).isEqualTo(1L);
        assertThat(reply.conflictIndex()).isEqualTo(1L);
        assertThat(node.log().lastIndex()).isEqualTo(1L);
    }

    @Test
    void followerRejectsStaleLeaderAppendEntries() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        electToLeader(node, 400);                       // leader, term 1
        node.step(new Input.MessageReceived(            // step down via term-2 vote request
                new RaftMessage.RequestVote(1, 2L, 1L, 1L)));
        node.step(new Input.Tick(900));                 // re-elect: leader, term 3
        node.step(new Input.MessageReceived(
                new RaftMessage.RequestVoteReply(1, 3L, true)));
        assertThat(node.role()).isEqualTo(Role.LEADER);
        assertThat(node.currentTerm()).isEqualTo(3L);

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(2, 2L, 0L, 0L, List.of(), 0L)));

        RaftMessage.AppendEntriesReply reply = soleAeReply(effects);
        assertThat(reply.success()).isFalse();
        assertThat(reply.term()).isEqualTo(3L);
        assertThat(node.role()).isEqualTo(Role.LEADER);
        // Term-1 no-op at index 1 and term-3 no-op at index 2; the stale leader
        // must not have appended or committed anything.
        assertThat(node.log().lastIndex()).isEqualTo(2L);
        assertThat(node.commitIndex()).isZero();
    }

    @Test
    void appendEntriesTruncatesConflictingTailPersistsAndIsIdempotent() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        appendTermOneEntry(node);   // log: [ (1, term 1) ]

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 1L, 0L, 0L,
                        List.of(new LogEntry(1, 2L, CMD, "s", 1L)), 0L)));

        assertThat(node.log().lastIndex()).isEqualTo(1L);
        assertThat(node.log().get(1).term()).isEqualTo(2L);
        assertThat(firstPersistIndex(effects)).isGreaterThanOrEqualTo(0);
        RaftMessage.AppendEntriesReply reply = soleAeReply(effects);
        assertThat(reply.success()).isTrue();
        assertThat(reply.matchIndex()).isEqualTo(1L);

        // Redelivery of the identical AppendEntries must not duplicate the entry.
        node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 1L, 0L, 0L,
                        List.of(new LogEntry(1, 2L, CMD, "s", 1L)), 0L)));
        assertThat(node.log().lastIndex()).isEqualTo(1L);
        assertThat(node.log().get(1).term()).isEqualTo(2L);
    }

    // ── Commitment rules ──────────────────────────────────────────────────────

    @Test
    void leaderDoesNotCommitOldTermEntryByReplicaCountAlone() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        // Leader in term 1 with an uncommitted term-1 command at index 2.
        electToLeader(node, 400);
        node.step(new Input.ClientCommand("session-a", 1L, CMD));
        // Higher-term leader exists: step down to term 2, then win term 3.
        node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 2L, 0L, 0L, List.of(), 0L)));
        node.step(new Input.Tick(900));
        node.step(new Input.MessageReceived(
                new RaftMessage.RequestVoteReply(1, 3L, true)));
        assertThat(node.role()).isEqualTo(Role.LEADER);
        assertThat(node.currentTerm()).isEqualTo(3L);
        assertThat(node.commitIndex()).isZero();

        // Follower 1 acknowledges only the old term-1 entry (index 2),
        // not the new term-3 no-op (index 3).
        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntriesReply(1, 3L, true, 2L, 0L, 0L)));

        // The term-1 entry has a majority (self + follower 1) but MUST NOT
        // commit without a majority-replicated current-term entry.
        assertThat(node.commitIndex()).isZero();
        assertThat(applies(effects)).isEmpty();
    }

    @Test
    void commitOldTermByCountBugCommitsOldTermEntryByCountAlone() {
        RaftNodeImpl bugged = new RaftNodeImpl(0, 3, BugMode.COMMIT_OLD_TERM_BY_COUNT);
        electToLeader(bugged, 400);
        bugged.step(new Input.ClientCommand("session-a", 1L, CMD));
        bugged.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 2L, 0L, 0L, List.of(), 0L)));
        bugged.step(new Input.Tick(900));
        bugged.step(new Input.MessageReceived(
                new RaftMessage.RequestVoteReply(1, 3L, true)));

        List<Effect> effects = bugged.step(new Input.MessageReceived(
                new RaftMessage.AppendEntriesReply(1, 3L, true, 2L, 0L, 0L)));

        // Detection proof: the correct implementation leaves commitIndex at 0 and
        // applies nothing; this bug mode commits the term-1 entry by count alone and
        // the entry reaches the state machine.
        assertThat(bugged.commitIndex()).isEqualTo(2L);
        assertThat(applies(effects)).containsExactly(new Effect.Apply(2L, CMD, "session-a", 1L));
    }

    @Test
    void leaderCommitsWhenCurrentTermEntryReachesMajorityAndRepliesToClient() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        electToLeader(node, 400);   // leader, term 1, no-op at index 1

        List<Effect> commandEffects = node.step(
                new Input.ClientCommand("session-a", 1L, CMD));
        assertThat(commandEffects.stream().noneMatch(Effect.Apply.class::isInstance)).isTrue();
        assertThat(firstPersistIndex(commandEffects)).isLessThan(firstSendIndex(commandEffects));
        assertThat(sends(commandEffects)).extracting(Effect.Send::destinationNodeId)
                .containsExactlyInAnyOrder(1, 2);

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntriesReply(1, 1L, true, 2L, 0L, 0L)));

        assertThat(node.commitIndex()).isEqualTo(2L);
        assertThat(node.lastApplied()).isEqualTo(2L);
        assertThat(applies(effects)).containsExactly(
                new Effect.Apply(2L, CMD, "session-a", 1L));

        // Effect.Reply carries a fresh byte[0], and record equality compares array
        // components by identity, so the reply must be asserted field by field.
        assertThat(replies(effects)).hasSize(1);
        Effect.Reply reply = replies(effects).getFirst();
        assertThat(reply.sessionId()).isEqualTo("session-a");
        assertThat(reply.sequenceNumber()).isEqualTo(1L);
        assertThat(reply.success()).isTrue();
        assertThat(reply.resultBytes()).isEmpty();
    }

    // ── State-machine application ─────────────────────────────────────────────

    @Test
    void followerAppliesCommittedEntriesOnlyAndSkipsNoOps() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 1L, 0L, 0L,
                        List.of(new LogEntry(1, 1L, new byte[0], "", -1L),
                                new LogEntry(2, 1L, CMD, "s", 1L)),
                        1L)));

        // leaderCommit=1: only the no-op is committed — nothing is applied.
        assertThat(node.commitIndex()).isEqualTo(1L);
        assertThat(applies(effects)).isEmpty();

        List<Effect> committed = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 1L, 0L, 0L,
                        List.of(new LogEntry(1, 1L, new byte[0], "", -1L),
                                new LogEntry(2, 1L, CMD, "s", 1L)),
                        2L)));

        assertThat(node.commitIndex()).isEqualTo(2L);
        assertThat(node.lastApplied()).isEqualTo(2L);
        assertThat(applies(committed)).containsExactly(
                new Effect.Apply(2L, CMD, "s", 1L));
    }

    @Test
    void applyBeforeCommitBugAppliesUncommittedClientCommandImmediately() {
        RaftNodeImpl bugged = new RaftNodeImpl(0, 3, BugMode.APPLY_BEFORE_COMMIT);
        electToLeader(bugged, 400);

        List<Effect> effects = bugged.step(new Input.ClientCommand("session-a", 1L, CMD));

        // Detection proof: the correct implementation applies nothing until the
        // entry is committed (test above asserts the same scenario has no Apply).
        assertThat(applies(effects)).isNotEmpty();
        assertThat(bugged.commitIndex()).isZero();
    }

    @Test
    void nonLeaderRedirectsClientCommand() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);

        List<Effect> effects = node.step(new Input.ClientCommand("session-a", 1L, CMD));

        assertThat(node.log().lastIndex()).isZero();
        assertThat(applies(effects)).isEmpty();
        assertThat(sends(effects)).isEmpty();
        assertThat(replies(effects)).hasSize(1);
        Effect.Reply redirect = replies(effects).getFirst();
        assertThat(redirect.success()).isFalse();
        assertThat(redirect.sessionId()).isEqualTo("session-a");
        assertThat(redirect.sequenceNumber()).isEqualTo(1L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static RaftMessage.AppendEntriesReply soleAeReply(List<Effect> effects) {
        List<Effect.Send> aeReplies = sends(effects).stream()
                .filter(send -> send.message() instanceof RaftMessage.AppendEntriesReply)
                .toList();
        assertThat(aeReplies).hasSize(1);
        return (RaftMessage.AppendEntriesReply) aeReplies.getFirst().message();
    }

    /** Delivers one term-1 command entry (index 1) to follower node 0 via AppendEntries. */
    private static void appendTermOneEntry(RaftNodeImpl node) {
        node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 1L, 0L, 0L,
                        List.of(new LogEntry(1, 1L, CMD, "s", 1L)), 0L)));
    }
}
