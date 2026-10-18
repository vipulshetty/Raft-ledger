package com.raftledger.raft;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.raftledger.raft.RaftEffects.firstPersistIndex;
import static com.raftledger.raft.RaftEffects.firstSendIndex;
import static com.raftledger.raft.RaftEffects.sends;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavioural tests for leader election, voting, log freshness and
 * term/vote persistence ordering in {@link RaftNodeImpl}.
 *
 * Mutation-detection tests additionally run the same scenario under the
 * relevant {@link BugMode} to prove the suite actually detects the planted bug.
 */
class RaftNodeElectionTest {

    private static final byte[] CMD = "transfer".getBytes(StandardCharsets.UTF_8);

    // ── Election start and persist-before-send ordering ───────────────────────

    @Test
    void startElectionIncrementsTermVotesForSelfAndPersistsBeforeRequestingVotes() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);

        // 400ms exceeds the default 300ms election timeout, so the timer has expired.
        List<Effect> effects = node.step(new Input.Tick(400));

        assertThat(node.role()).isEqualTo(Role.CANDIDATE);
        assertThat(node.currentTerm()).isEqualTo(1L);
        assertThat(node.votedFor()).isEqualTo(0);

        // The Persist that makes term=1 + votedFor=self durable must exist AND precede
        // the first RequestVote. (Asserting only "persist index < send index" would pass
        // vacuously at -1 < 0 when no Persist was emitted at all.)
        int persist = firstPersistIndex(effects,
                state -> state.currentTerm() == 1L && state.votedFor() == 0);
        assertThat(persist).isGreaterThanOrEqualTo(0);
        int firstSend = firstSendIndex(effects);
        assertThat(firstSend).isGreaterThan(persist);

        assertThat(effects.get(firstSend))
                .isEqualTo(new Effect.Send(1, new RaftMessage.RequestVote(0, 1L, 0L, 0L)));
        // one vote request per peer
        assertThat(sends(effects)).extracting(Effect.Send::destinationNodeId)
                .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void voteNotPersistedBugOmitsPersistBeforeVoteRequests() {
        RaftNodeImpl bugged = new RaftNodeImpl(0, 3, BugMode.VOTE_NOT_PERSISTED);

        List<Effect> effects = bugged.step(new Input.Tick(400));

        // Detection proof: the correct implementation persists first (see test
        // above); this bug mode must violate exactly that ordering.
        assertThat(firstPersistIndex(effects)).isEqualTo(-1);
        assertThat(firstSendIndex(effects)).isGreaterThanOrEqualTo(0);
    }

    // ── Voting rules ──────────────────────────────────────────────────────────

    @Test
    void followerGrantsVoteOnlyOncePerTerm() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);

        List<Effect> first = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 1L, 0L, 0L)));
        assertThat(node.votedFor()).isEqualTo(1);
        assertThat(voteGranted(node, first)).isTrue();
        // Vote must be durable before the granting reply leaves the node.
        int votePersist = firstPersistIndex(first, state -> state.votedFor() == 1);
        assertThat(votePersist).isGreaterThanOrEqualTo(0);
        assertThat(votePersist).isLessThan(firstSendIndex(first));

        List<Effect> second = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(2, 1L, 2L, 1L)));
        assertThat(voteGranted(node, second)).isFalse();
        assertThat(node.votedFor()).isEqualTo(1);

        // Re-request from the already-chosen candidate is granted again.
        List<Effect> repeat = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 1L, 2L, 1L)));
        assertThat(voteGranted(node, repeat)).isTrue();
    }

    @Test
    void voterRejectsCandidateWithStaleLog() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        seedLogWithTwoTermOneEntries(node);   // lastTerm=1, lastIndex=2

        // Candidate with an empty log: fresher terms alone must not win.
        List<Effect> stale = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 5L, 0L, 0L)));
        assertThat(voteGranted(node, stale)).isFalse();
        assertThat(node.votedFor()).isEqualTo(-1);

        // Candidate whose log is at least as up-to-date: granted.
        List<Effect> fresh = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(2, 5L, 2L, 1L)));
        assertThat(voteGranted(node, fresh)).isTrue();
        assertThat(node.votedFor()).isEqualTo(2);
    }

    @Test
    void logFreshnessComparesTermFirstThenIndex() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        seedLogWithTwoTermOneEntries(node);   // lastTerm=1, lastIndex=2

        // Same term, shorter log -> denied.
        assertThat(voteGranted(node, node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 5L, 1L, 1L))))).isFalse();

        // Higher term but shorter log -> granted: term dominates index.
        assertThat(voteGranted(node, node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(2, 6L, 1L, 2L))))).isTrue();
    }

    @Test
    void noLogUpToDateCheckBugLetsStaleCandidateGetTheVote() {
        RaftNodeImpl bugged = new RaftNodeImpl(0, 3, BugMode.NO_LOG_UPTODATE_CHECK);
        seedLogWithTwoTermOneEntries(bugged);

        List<Effect> stale = bugged.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 5L, 0L, 0L)));

        // Detection proof: correct implementation rejects (test above).
        assertThat(voteGranted(bugged, stale)).isTrue();
    }

    // ── Election safety: one vote counts once ─────────────────────────────────

    @Test
    void duplicatedVoteReplyFromTheSamePeerCannotElectACandidate() {
        RaftNodeImpl node = new RaftNodeImpl(0, 5);   // majority = 3
        node.step(new Input.Tick(400));
        assertThat(node.role()).isEqualTo(Role.CANDIDATE);

        // Self vote (1) plus one real peer vote (2). Network redelivers the peer vote.
        node.step(new Input.MessageReceived(new RaftMessage.RequestVoteReply(1, 1L, true)));
        List<Effect> duplicate = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVoteReply(1, 1L, true)));

        assertThat(node.role()).isEqualTo(Role.CANDIDATE);
        assertThat(duplicate).isEmpty();

        // A genuinely distinct peer reaches the majority and promotes.
        node.step(new Input.MessageReceived(new RaftMessage.RequestVoteReply(2, 1L, true)));
        assertThat(node.role()).isEqualTo(Role.LEADER);
    }

    @Test
    void staleTermVoteReplyCannotPromoteACandidate() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        node.step(new Input.Tick(400));   // candidate in term 1

        // Reply from a previous term, and a denial from the current term.
        assertThat(node.step(new Input.MessageReceived(
                new RaftMessage.RequestVoteReply(1, 0L, true)))).isEmpty();
        assertThat(node.role()).isEqualTo(Role.CANDIDATE);
        node.step(new Input.MessageReceived(new RaftMessage.RequestVoteReply(1, 1L, false)));
        assertThat(node.role()).isEqualTo(Role.CANDIDATE);
    }

    // ── Term handling ─────────────────────────────────────────────────────────

    @Test
    void higherTermMessageStepsLeaderDownAndPersistsBeforeReplying() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        electToLeader(node, 400);   // leader in term 1
        assertThat(node.role()).isEqualTo(Role.LEADER);

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(1, 3L, 0L, 0L, List.of(), 0L)));

        assertThat(node.role()).isEqualTo(Role.FOLLOWER);
        assertThat(node.currentTerm()).isEqualTo(3L);
        assertThat(node.votedFor()).isEqualTo(-1);

        int persist = firstPersistIndex(effects);
        assertThat(persist).isGreaterThanOrEqualTo(0);
        assertThat(persist).isLessThan(firstSendIndex(effects));
        assertThat(((Effect.Persist) effects.get(persist)).state())
                .isEqualTo(new PersistentState(3L, -1, 1));
    }

    // ── Leadership promotion and the no-op entry ──────────────────────────────

    @Test
    void majorityOfVotesPromotesCandidateAppendsNoOpAndSendsHeartbeats() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        node.step(new Input.Tick(400));   // candidate, term 1

        List<Effect> effects = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVoteReply(1, 1L, true)));

        assertThat(node.role()).isEqualTo(Role.LEADER);
        assertThat(node.currentLeader()).isEqualTo(0);

        LogEntry noOp = node.log().get(1);
        assertThat(noOp).isNotNull();
        assertThat(noOp.isNoOp()).isTrue();
        assertThat(noOp.term()).isEqualTo(1L);

        int persist = firstPersistIndex(effects);
        assertThat(persist).isGreaterThanOrEqualTo(0);
        assertThat(((Effect.Persist) effects.get(persist)).state().logLength()).isEqualTo(1);

        // Initial heartbeats go out immediately after promotion.
        assertThat(sends(effects)).extracting(Effect.Send::destinationNodeId)
                .containsExactlyInAnyOrder(1, 2);
        Effect.Send heartbeat = sends(effects).getFirst();
        RaftMessage.AppendEntries ae =
                (RaftMessage.AppendEntries) heartbeat.message();
        assertThat(ae.term()).isEqualTo(1L);
        assertThat(ae.prevLogIndex()).isZero();
        assertThat(ae.entries()).hasSize(1);
        assertThat(ae.entries().getFirst().isNoOp()).isTrue();
    }

    // ── Restart recovery ──────────────────────────────────────────────────────

    @Test
    void restartResetsVolatileStateButKeepsPersistedTermAndVote() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        electToLeader(node, 400);   // term 1, votedFor=0, no-op at index 1

        // Storage as the runtime would read it back: the term/vote from the node's last
        // Persist, plus the one log record the WAL still holds.
        List<Effect> effects = node.step(new Input.Restart(
                new PersistentState(1L, 0, 1), List.of(LogEntry.noOp(1, 1L))));

        assertThat(effects).containsExactly(new Effect.ResetElectionTimer());
        assertThat(node.role()).isEqualTo(Role.FOLLOWER);
        assertThat(node.currentLeader()).isEqualTo(-1);
        // Persisted state survives the restart.
        assertThat(node.currentTerm()).isEqualTo(1L);
        assertThat(node.votedFor()).isEqualTo(0);
        assertThat(node.log().lastIndex()).isEqualTo(1L);
    }

    @Test
    void durableVoteStillBlocksOtherCandidatesAfterARestart() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        electToLeader(node, 400);           // voted for itself in term 1
        node.step(new Input.Restart(        // crash: volatile state gone, term/vote/log durable
                new PersistentState(1L, 0, 1), List.of(LogEntry.noOp(1, 1L))));

        // Election safety across a crash: term 1 is already claimed by node 0,
        // so a different candidate in term 1 must be denied even though the
        // restarting node is now a plain follower with a fresher-looking rival.
        List<Effect> sameTerm = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 1L, 9L, 9L)));
        assertThat(voteGranted(node, sameTerm)).isFalse();
        assertThat(node.votedFor()).isZero();

        // In a NEW term the persisted vote is cleared and the rival is granted.
        List<Effect> nextTerm = node.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 2L, 9L, 9L)));
        assertThat(voteGranted(node, nextTerm)).isTrue();
        assertThat(node.votedFor()).isEqualTo(1);
        assertThat(node.currentTerm()).isEqualTo(2L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean voteGranted(RaftNode node, List<Effect> effects) {
        return sends(effects).stream()
                .filter(send -> send.message() instanceof RaftMessage.RequestVoteReply)
                .map(send -> (RaftMessage.RequestVoteReply) send.message())
                .allMatch(RaftMessage.RequestVoteReply::voteGranted)
                && sends(effects).stream()
                .anyMatch(send -> send.message() instanceof RaftMessage.RequestVoteReply);
    }

    /**
     * Drives a single-election promotion: node 0 becomes leader of a 3-node cluster in
     * term 1. {@code tickTimeMs} must exceed the node's election timeout, otherwise the
     * Tick is ignored and the helper silently produces a follower (which used to make the
     * scenarios below assert against a node that had never been a leader at all).
     */
    static void electToLeader(RaftNodeImpl node, long tickTimeMs) {
        node.step(new Input.Tick(tickTimeMs));
        assertThat(node.role()).isEqualTo(Role.CANDIDATE);
        node.step(new Input.MessageReceived(new RaftMessage.RequestVoteReply(1, node.currentTerm(), true)));
        assertThat(node.role()).isEqualTo(Role.LEADER);
    }

    /** Fills node 0's log with a no-op and one command, both from term 1, via a real election. */
    static void seedLogWithTwoTermOneEntries(RaftNodeImpl node) {
        electToLeader(node, 400);
        node.step(new Input.ClientCommand("session-a", 1L, CMD));
    }
}
