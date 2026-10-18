package com.raftledger.raft;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Fast catch-up: how a leader uses a follower's conflict hint to move {@code nextIndex}
 * backwards until the two logs agree, and how the follower's conflicting suffix is then
 * repaired.
 *
 * <p>Reference: Raft thesis §5.3 (the optimised AppendEntries reply). A follower answers a
 * rejected AppendEntries with the term of the entry at {@code prevLogIndex} and the first
 * index of that term in its own log. The leader then either
 * <ul>
 *   <li>knows that term: send from one past the LAST index the leader itself holds for it,
 *       because everything from there on is a different term in both logs; or</li>
 *   <li>does not know that term: send from the follower's reported first index, because the
 *       follower's copy of that term cannot survive next to the leader's entries;</li>
 * </ul>
 * falling back to a single-index step when the follower had no entry to point at.
 *
 * <p>Every log in these tests is produced by real elections and real replication over a
 * partitioned {@link RaftCluster}; none is hand-seeded. That matters here: the log matching
 * property makes a term occupy one contiguous range that starts at the same index in every
 * log that holds it, so many plausible-looking log pairs are actually unreachable and would
 * "verify" a branch no execution can enter.
 */
class RaftNodeCatchUpTest {

    // ── The follower is simply shorter than the leader's hint can describe ──────

    @Test
    void followerWithNoEntryAtPrevLogIndexReportsNoConflictHint() {
        RaftCluster c = leaderAheadOfPartitionedFollower();

        // B is promoted holding four entries; C stopped at two and cannot name a conflict term.
        c.tick(1, 800);

        assertThat(c.rejectionsFrom(2, 1))
                .extracting(RaftMessage.AppendEntriesReply::conflictTerm,
                        RaftMessage.AppendEntriesReply::conflictIndex)
                .containsExactly(tuple(0L, 0L), tuple(0L, 0L));
    }

    @Test
    void missingTailForcesOneIndexPerRoundTripBecauseTheLeaderCannotJump() {
        RaftCluster c = leaderAheadOfPartitionedFollower();

        c.tick(1, 800);

        // nextIndex walked 5 -> 4 -> 3, i.e. one rejected round trip per index of distance,
        // while the peer that held the same entries (A) was accepted immediately.
        // A follower in this position could report lastLogIndex + 1 and end the walk at once;
        // this is the confirmed liveness shortfall, recorded here so the cost is visible.
        assertThat(c.nextIndexesSentTo(1, 2)).containsExactly(5L, 4L, 3L);
        assertThat(c.nextIndexesSentTo(1, 0)).containsExactly(5L);
    }

    @Test
    void everyRejectionMovesNextIndexStrictlyBackwardsSoCatchUpCannotLivelock() {
        RaftCluster c = leaderAheadOfPartitionedFollower();

        c.tick(1, 800);

        List<Long> attempts = c.nextIndexesSentTo(1, 2);
        for (int i = 1; i < attempts.size(); i++) {
            assertThat(attempts.get(i)).isLessThan(attempts.get(i - 1));
        }
        assertThat(attempts).doesNotHaveDuplicates();
        assertThat(c.termProfile(2)).isEqualTo(c.termProfile(1));
    }

    @Test
    void repairedFollowerConvergesAndAppliesTheSameCommittedEntries() {
        RaftCluster c = leaderAheadOfPartitionedFollower();
        c.tick(1, 800);

        c.clientCommand(1, 50L);
        // A commit index travels to a follower on the next AppendEntries, so one heartbeat
        // is required before the two nodes can be expected to agree.
        c.tick(1, 900);

        assertThat(c.termProfile(2)).isEqualTo(c.termProfile(1));
        assertThat(c.node(2).commitIndex()).isEqualTo(c.node(1).commitIndex());
        assertThat(c.appliedIndices(2)).isEqualTo(c.appliedIndices(1));
    }

    // ── Conflict term the leader also holds: trim to one past the leader's last index ──

    @Test
    void knownConflictTermTrimsNextIndexToJustPastTheLeadersLastIndexOfIt() {
        RaftCluster c = figureEightDivergence();
        assertThat(c.termRange(1, 1)).containsExactly(1L, 5L);   // F holds five term-1 entries
        assertThat(c.termRange(2, 1)).containsExactly(1L, 2L);   // R holds only two

        c.tick(2, 1200);

        assertThat(c.rejectionsFrom(1, 2))
                .extracting(RaftMessage.AppendEntriesReply::conflictTerm,
                        RaftMessage.AppendEntriesReply::conflictIndex)
                .containsExactly(tuple(1L, 1L));

        // R's last term-1 index is 2, so the retry starts at 3: one round trip, not five.
        assertThat(c.nextIndexesSentTo(2, 1)).containsExactly(6L, 3L);
    }

    @Test
    void knownConflictTermTrimDiscardsOnlyTheUncommittedTail() {
        RaftCluster c = figureEightDivergence();

        c.tick(2, 1200);

        // The committed term-1 prefix survives; the follower's extra term-1 entries do not.
        assertThat(c.termRange(1, 1)).containsExactly(1L, 2L);
        assertThat(c.termProfile(1)).isEqualTo(c.termProfile(2));
        assertThat(c.node(1).commitIndex()).isGreaterThanOrEqualTo(2L);
    }

    // ── Conflict terms the leader has never seen: jump to the follower's reported start ──

    @Test
    void unknownConflictTermJumpsToTheIndexTheFollowerReported() {
        RaftCluster c = leadersWithDivergentTermRanges();
        assertThat(c.termRange(1, 5)).containsExactly(7L, 9L);   // F's term-5 range
        assertThat(c.termRange(0, 5)).isNull();                  // L has never stored term 5

        c.tick(0, 2400);

        RaftMessage.AppendEntriesReply first = c.rejectionsFrom(1, 0).getFirst();
        assertThat(first.conflictTerm()).isEqualTo(5L);
        assertThat(first.conflictIndex()).isEqualTo(7L);
        assertThat(c.nextIndexesSentTo(0, 1)).startsWith(10L, 7L);
    }

    @Test
    void successiveUnknownTermsWalkBackOneTermRangeAtATime() {
        RaftCluster c = leadersWithDivergentTermRanges();

        c.tick(0, 2400);

        // Two divergent ranges below the leader's log end, so two hints and two jumps:
        // term 5 at 7..9, then term 3 at 3..6, then the shared term-1 prefix accepts.
        assertThat(c.rejectionsFrom(1, 0))
                .extracting(RaftMessage.AppendEntriesReply::conflictTerm,
                        RaftMessage.AppendEntriesReply::conflictIndex)
                .containsExactly(tuple(5L, 7L), tuple(3L, 3L));
        assertThat(c.nextIndexesSentTo(0, 1)).containsExactly(10L, 7L, 3L);
    }

    @Test
    void repairedSuffixLeavesBothLogsWithTheSameTermProfile() {
        RaftCluster c = leadersWithDivergentTermRanges();

        c.tick(0, 2400);
        c.clientCommand(0, 60L);
        c.tick(0, 2500);                      // let the new commit index reach the follower

        assertThat(c.termRange(1, 5)).isNull();
        assertThat(c.termRange(1, 3)).isNull();
        assertThat(c.termProfile(1)).isEqualTo(c.termProfile(0));
        assertThat(c.appliedIndices(1)).isEqualTo(c.appliedIndices(0));
    }

    // ── Scenario builders: each log below is the product of the messages above it ──────

    /**
     * Three nodes. A wins term 1 and replicates two entries to everyone, then C is cut off
     * while A writes two more. B, which holds them, wins term 2 and is promoted with a log
     * that is two entries longer than C's, so its first AppendEntries to C points past C's log.
     */
    private static RaftCluster leaderAheadOfPartitionedFollower() {
        int a = 0, b = 1, cutOffFollower = 2;
        RaftCluster c = new RaftCluster(3);

        c.tick(a, 400);
        c.clientCommand(a, 1L);

        c.cut(a, cutOffFollower);
        c.cut(cutOffFollower, a);
        c.clientCommand(a, 2L);
        c.clientCommand(a, 3L);

        assertThat(c.termProfile(cutOffFollower)).isEqualTo("1:t1 2:t1");
        assertThat(c.termProfile(b)).isEqualTo("1:t1 2:t1 3:t1 4:t1");
        assertThat(c.node(b).role()).isNotEqualTo(Role.LEADER);
        return c;
    }

    /**
     * Five nodes, the Figure 8 shape. P wins term 1 and stores five entries, but only F keeps
     * receiving them. Q then wins term 2 with R and S and writes a term-2 tail that never
     * reaches F. R wins term 3, so its term-1 range (two entries) is shorter than F's (five),
     * and its first AppendEntries to F asks for a term-2 predecessor F does not have.
     */
    private static RaftCluster figureEightDivergence() {
        int p = 0, f = 1, r = 2, q = 3;
        RaftCluster c = new RaftCluster(5);

        c.tick(p, 400);
        c.clientCommand(p, 1L);

        c.cut(p, r); c.cut(p, q); c.cut(p, 4);
        c.clientCommand(p, 2L);
        c.clientCommand(p, 3L);
        c.clientCommand(p, 4L);

        c.cut(q, f); c.cut(q, p);
        c.tick(q, 800);
        c.clientCommand(q, 5L);
        c.clientCommand(q, 6L);

        assertThat(c.node(r).role()).isEqualTo(Role.FOLLOWER);
        assertThat(c.termProfile(f)).isEqualTo("1:t1 2:t1 3:t1 4:t1 5:t1");
        assertThat(c.termProfile(r)).isEqualTo("1:t1 2:t1 3:t2 4:t2 5:t2");
        return c;
    }

    /**
     * Five nodes where two leaders write different terms over the same index range, which is
     * what makes a conflict hint point at a term the leader cannot find at all.
     *
     * <ol>
     *   <li>Y wins term 1; all nodes store indices 1..2.</li>
     *   <li>T wins term 2 and stores 3..6, but its entry batches reach only L.</li>
     *   <li>F wins term 3 with X and Y and stores 3..6 alone, overwriting nothing.</li>
     *   <li>L wins term 4 (X and Y still hold term 1, so they accept its longer log) and
     *       stores 7..9 alone.</li>
     *   <li>F wins term 5 (X and Y again) and stores 7..9, so both logs now end at index 9
     *       with different terms.</li>
     * </ol>
     * The links from L then heal, leaving L to be elected once more and catch F up.
     */
    private static RaftCluster leadersWithDivergentTermRanges() {
        int l = 0, f = 1, x = 2, y = 3, t = 4;
        RaftCluster c = new RaftCluster(5);

        c.tick(y, 400);
        c.clientCommand(y, 1L);

        c.blackholeEntries(t, f); c.blackholeEntries(t, x); c.blackholeEntries(t, y);
        c.tick(t, 800);
        c.clientCommand(t, 2L);
        c.clientCommand(t, 3L);
        c.clientCommand(t, 4L);

        c.blackholeEntries(f, x); c.blackholeEntries(f, y);
        c.blackholeEntries(f, l); c.blackholeEntries(f, t);
        c.tick(f, 1200);
        c.clientCommand(f, 5L);
        c.clientCommand(f, 6L);
        c.clientCommand(f, 7L);

        c.blackholeEntries(l, x); c.blackholeEntries(l, y); c.blackholeEntries(l, f);
        c.tick(l, 1600);
        c.clientCommand(l, 8L);
        c.clientCommand(l, 9L);

        c.tick(f, 2000);
        c.clientCommand(f, 10L);
        c.clientCommand(f, 11L);

        assertThat(c.termProfile(f)).isEqualTo("1:t1 2:t1 3:t3 4:t3 5:t3 6:t3 7:t5 8:t5 9:t5");
        assertThat(c.termProfile(l)).isEqualTo("1:t1 2:t1 3:t2 4:t2 5:t2 6:t2 7:t4 8:t4 9:t4");

        c.heal(l, f); c.heal(l, x); c.heal(l, y);
        return c;
    }
}
