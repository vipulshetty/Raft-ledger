package com.raftledger.raft;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.raftledger.raft.RaftEffects.applies;
import static com.raftledger.raft.RaftEffects.sends;
import static com.raftledger.raft.RaftNodeElectionTest.electToLeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Recovery after a crash, per {@link Input.Restart}: the node becomes exactly what
 * storage held, never what its memory happened to contain.
 *
 * <p>These tests supply the durable snapshot by hand, which is the only way to
 * distinguish "the core restores persisted state" from "the object was never cleared".
 * A test that restarts a node and then finds the term it already had proves nothing
 * unless an older snapshot is also handed to it.
 */
class RaftNodeRestartRecoveryTest {

    private static final byte[] CMD_A = "transfer-a".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CMD_B = "transfer-b".getBytes(StandardCharsets.UTF_8);

    @Test
    void recoveryAdoptsTheDurableSnapshotInsteadOfItsOwnMemory() {
        RaftNodeImpl node = new RaftNodeImpl(0, 3);
        electToLeader(node, 400);               // memory: term 1, votedFor 0, one no-op

        // Storage is behind memory: the promotion write never reached the disk.
        node.step(Input.Restart.fromEmptyStorage());

        assertThat(node.currentTerm()).isZero();
        assertThat(node.votedFor()).isEqualTo(-1);
        assertThat(node.log().lastIndex()).isZero();
        assertThat(node.role()).isEqualTo(Role.FOLLOWER);
    }

    @Test
    void electionSafetyAcrossACrashRestsOnTheVoteBeingDurable() {
        RaftNodeImpl voter = new RaftNodeImpl(0, 3);

        // Grant one vote in term 1; the node persists term+vote before the reply leaves.
        List<Effect> first = voter.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(1, 1L, 0L, 0L)));
        assertThat(voter.votedFor()).isEqualTo(1);
        assertThat(voteReply(first).voteGranted()).isTrue();

        // A correct runtime fsyncs that Persist first, so the vote is on disk and comes back.
        RaftNodeImpl recovered = new RaftNodeImpl(0, 3);
        recovered.step(new Input.MessageReceived(new RaftMessage.RequestVote(1, 1L, 0L, 0L)));
        recovered.step(new Input.Restart(new PersistentState(1L, 1, 0), List.of()));
        List<Effect> rivalAfterFsync = recovered.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(2, 1L, 0L, 0L)));
        assertThat(voteReply(rivalAfterFsync).voteGranted()).isFalse();

        // If the vote was lost, the node comes back having voted for nobody and grants the
        // rival a vote in the SAME term: two leaders can then each claim a majority. This is
        // what VOTE_NOT_PERSISTED means, stated at the core boundary rather than in a javadoc.
        voter.step(Input.Restart.fromEmptyStorage());
        assertThat(voter.currentTerm()).isZero();
        assertThat(voter.votedFor()).isEqualTo(-1);

        List<Effect> rivalAfterLostVote = voter.step(new Input.MessageReceived(
                new RaftMessage.RequestVote(2, 1L, 0L, 0L)));

        assertThat(voteReply(rivalAfterLostVote).voteGranted()).isTrue();
        assertThat(voter.votedFor()).isEqualTo(2);
    }

    @Test
    void restartDropsLogRecordsThatNeverReachedStorage() {
        RaftNodeImpl follower = new RaftNodeImpl(1, 3);

        // Leader 0 replicates three entries; the follower's third WAL record was unsynced.
        follower.step(new Input.MessageReceived(new RaftMessage.AppendEntries(
                0, 1L, 0L, 0L,
                List.of(entry(1, 1L, CMD_A), entry(2, 1L, CMD_B), entry(3, 1L, CMD_A)), 0L)));
        assertThat(follower.log().lastIndex()).isEqualTo(3L);

        follower.step(new Input.Restart(new PersistentState(1L, -1, 2),
                List.of(entry(1, 1L, CMD_A), entry(2, 1L, CMD_B))));

        assertThat(follower.log().lastIndex()).isEqualTo(2L);
        assertThat(follower.log().get(3)).isNull();

        // The leader's next batch continues from what storage actually holds.
        List<Effect> repair = follower.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(0, 1L, 2L, 1L, List.of(entry(3, 1L, CMD_B)), 3L)));

        assertThat(follower.log().get(3).command()).isEqualTo(CMD_B);
        assertThat(follower.commitIndex()).isEqualTo(3L);
        assertThat(applies(repair)).extracting(Effect.Apply::logIndex).contains(3L);
    }

    @Test
    void restartRejectsASnapshotThatContradictsItsOwnLogLength() {
        // The term/vote record and the WAL must agree, or recovery would install a log the
        // persisted metadata does not describe.
        assertThatThrownBy(() -> new Input.Restart(new PersistentState(2L, 1, 3),
                List.of(entry(1, 1L, CMD_A))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("logLength 3 disagrees with 1 durable log records");
    }

    @Test
    void recoveredNodeReLearnsItsCommitIndexAndAppliesThoseEntriesAgain() {
        RaftNodeImpl follower = new RaftNodeImpl(1, 3);
        List<Effect> replicated = follower.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(0, 1L, 0L, 0L,
                        List.of(entry(1, 1L, CMD_A), entry(2, 1L, CMD_B)), 2L)));
        assertThat(applies(replicated)).extracting(Effect.Apply::logIndex).containsExactly(1L, 2L);

        // commitIndex and lastApplied are volatile, so recovery restarts them at zero and the
        // leader's heartbeat re-advances them: entries 1 and 2 reach Apply a second time.
        // Exactly-once therefore lives in the state machine's replicated idempotency key, not
        // in the node — that is Increment 4, and this test pins the obligation.
        follower.step(new Input.Restart(new PersistentState(1L, -1, 2),
                List.of(entry(1, 1L, CMD_A), entry(2, 1L, CMD_B))));
        assertThat(follower.commitIndex()).isZero();

        List<Effect> heartbeat = follower.step(new Input.MessageReceived(
                new RaftMessage.AppendEntries(0, 1L, 2L, 1L, List.of(), 2L)));

        assertThat(follower.commitIndex()).isEqualTo(2L);
        assertThat(applies(heartbeat)).extracting(Effect.Apply::logIndex).containsExactly(1L, 2L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LogEntry entry(long index, long term, byte[] command) {
        return new LogEntry(index, term, command, "session-a", index);
    }

    /** The single vote reply among the declared effects; absent or duplicated is a failure. */
    private static RaftMessage.RequestVoteReply voteReply(List<Effect> effects) {
        List<RaftMessage.RequestVoteReply> replies = sends(effects).stream()
                .map(Effect.Send::message)
                .filter(RaftMessage.RequestVoteReply.class::isInstance)
                .map(RaftMessage.RequestVoteReply.class::cast)
                .toList();
        assertThat(replies).hasSize(1);
        return replies.getFirst();
    }
}
