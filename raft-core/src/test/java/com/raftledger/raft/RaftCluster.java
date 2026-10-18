package com.raftledger.raft;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Deterministic in-process Raft cluster: the message equivalent of the simulator.
 *
 * <p>Nodes are real {@link RaftNodeImpl} instances. Every {@code Send} effect is routed
 * to its destination as a {@code MessageReceived} input; links can be cut so a message is
 * dropped, which is how a test produces the log histories the protocol could actually have
 * produced. Logs are never hand-seeded, so a conflict hint asserted by a test is one a real
 * follower really reported against a real leader.
 *
 * TEST SCOPE ONLY — lives in src/test, not shipped.
 */
final class RaftCluster {

    private static final byte[] CMD = "cmd".getBytes(StandardCharsets.UTF_8);

    /** A message moved from one node toward another, delivered or dropped. */
    record Routed(int from, int to, RaftMessage message, boolean dropped) {}

    /** A state-machine application performed by one node. */
    record Applied(int node, long index) {}

    private record Delivery(int from, int to, RaftMessage message) {}

    private final RaftNodeImpl[] nodes;
    private final Set<String> cutLinks = new LinkedHashSet<>();
    private final Set<String> entryBlackholes = new LinkedHashSet<>();
    private final Deque<Delivery> pending = new ArrayDeque<>();
    private final List<Routed> routed = new ArrayList<>();
    private final List<Applied> applies = new ArrayList<>();

    private int deliveries;

    RaftCluster(int clusterSize) {
        this.nodes = new RaftNodeImpl[clusterSize];
        for (int i = 0; i < clusterSize; i++) nodes[i] = new RaftNodeImpl(i, clusterSize);
    }

    // ── Wiring ────────────────────────────────────────────────────────────────

    RaftNodeImpl node(int id) { return nodes[id]; }

    /** Drop every message from {@code from} to {@code to} until {@link #heal}. */
    void cut(int from, int to) { cutLinks.add(link(from, to)); }

    /** Restore a link completely: clears both the cut and the entry blackhole. */
    void heal(int from, int to) {
        cutLinks.remove(link(from, to));
        entryBlackholes.remove(link(from, to));
    }

    /**
     * Drop only {@code from} to {@code to} AppendEntries batches, while votes and replies
     * still flow. This is the standard "data path fails after the control path won an
     * election" fault: the leader is elected, yet the peer never stores its entries, so
     * the two logs diverge at the same index with different terms.
     */
    void blackholeEntries(int from, int to) {
        entryBlackholes.add(link(from, to));
    }

    private static String link(int from, int to) { return from + ">" + to; }

    private boolean isCut(int from, int to) { return cutLinks.contains(link(from, to)); }

    // ── Driving ───────────────────────────────────────────────────────────────

    void tick(int id, long virtualTimeMs) {
        submit(id, nodes[id].step(new Input.Tick(virtualTimeMs)));
    }

    void clientCommand(int id, long sequenceNumber) {
        submit(id, nodes[id].step(new Input.ClientCommand("session", sequenceNumber, CMD)));
    }

    private void submit(int from, List<Effect> effects) {
        for (Effect effect : effects) enqueue(from, effect);
        drain();
    }

    private void enqueue(int from, Effect effect) {
        if (effect instanceof Effect.Send send) {
            pending.add(new Delivery(from, send.destinationNodeId(), send.message()));
        } else if (effect instanceof Effect.Apply apply) {
            applies.add(new Applied(from, apply.logIndex()));
        }
        // Persist, ResetElectionTimer and Reply change no log state this harness observes.
    }

    private void drain() {
        while (!pending.isEmpty()) {
            Delivery d = pending.removeFirst();
            if (d.from() == d.to()) continue;
            if (isCut(d.from(), d.to()) || isBlackholed(d)) {
                routed.add(new Routed(d.from(), d.to(), d.message(), true));
                continue;
            }
            if (++deliveries > 100_000) throw new AssertionError("delivery budget exhausted");
            routed.add(new Routed(d.from(), d.to(), d.message(), false));
            List<Effect> effects = nodes[d.to()].step(new Input.MessageReceived(d.message()));
            for (Effect effect : effects) enqueue(d.to(), effect);
        }
    }

    /** Entry-carrying AppendEntries on a blackholed link are lost; heartbeats still pass. */
    private boolean isBlackholed(Delivery d) {
        if (!(d.message() instanceof RaftMessage.AppendEntries ae)) return false;
        if (ae.entries().isEmpty()) return false;
        return entryBlackholes.contains(link(d.from(), d.to()));
    }

    // ── Observation ───────────────────────────────────────────────────────────

    /** Log indices one node applied to its state machine, in order. */
    List<Long> appliedIndices(int node) {
        List<Long> out = new ArrayList<>();
        for (Applied a : applies) {
            if (a.node() == node) out.add(a.index());
        }
        return out;
    }

    private List<Routed> between(int from, int to) {
        List<Routed> out = new ArrayList<>();
        for (Routed r : routed) {
            if (r.from() == from && r.to() == to) out.add(r);
        }
        return out;
    }

    /** AppendEntries a leader delivered to one peer, in send order. */
    List<RaftMessage.AppendEntries> appendEntriesTo(int leader, int peer) {
        List<RaftMessage.AppendEntries> out = new ArrayList<>();
        for (Routed r : between(leader, peer)) {
            if (!r.dropped() && r.message() instanceof RaftMessage.AppendEntries ae) out.add(ae);
        }
        return out;
    }

    /**
     * The {@code nextIndex} value a leader used for a peer at each send. The wire has no
     * nextIndex field, so it is read back from prevLogIndex + 1 of the AppendEntries the
     * leader actually emitted.
     */
    List<Long> nextIndexesSentTo(int leader, int peer) {
        List<Long> out = new ArrayList<>();
        for (RaftMessage.AppendEntries ae : appendEntriesTo(leader, peer)) {
            out.add(ae.prevLogIndex() + 1);
        }
        return out;
    }

    /** Rejections a peer produced against a leader, whether or not the leader received them. */
    List<RaftMessage.AppendEntriesReply> rejectionsFrom(int peer, int leader) {
        List<RaftMessage.AppendEntriesReply> out = new ArrayList<>();
        for (Routed r : between(peer, leader)) {
            if (r.message() instanceof RaftMessage.AppendEntriesReply ar && !ar.success()) {
                out.add(ar);
            }
        }
        return out;
    }

    /** Compact log view, e.g. {@code 1:t1 2:t1 3:t2}. */
    String termProfile(int id) {
        StringBuilder sb = new StringBuilder();
        for (long i = 1; i <= nodes[id].log().lastIndex(); i++) {
            if (i > 1) sb.append(' ');
            sb.append(i).append(":t").append(nodes[id].log().get(i).term());
        }
        return sb.toString();
    }

    /** Inclusive index range one term occupies in a node's log, or null when absent. */
    long[] termRange(int id, long term) {
        Log log = nodes[id].log();
        long first = -1L, last = -1L;
        for (long i = 1; i <= log.lastIndex(); i++) {
            if (log.get(i).term() == term) {
                if (first < 0) first = i;
                last = i;
            }
        }
        return first < 0 ? null : new long[]{first, last};
    }
}
