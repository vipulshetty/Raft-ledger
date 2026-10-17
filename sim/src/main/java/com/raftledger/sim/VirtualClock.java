package com.raftledger.sim;

import java.util.PriorityQueue;

public final class VirtualClock {
    private final PriorityQueue<ScheduledEvent> events = new PriorityQueue<>();
    private long now;
    private long nextSequence;

    public long now() {
        return now;
    }

    public long scheduleAt(long virtualTime, Runnable action) {
        if (virtualTime < now) throw new IllegalArgumentException("Cannot schedule an event in the past");
        long sequence = nextSequence++;
        events.add(new ScheduledEvent(virtualTime, sequence, action));
        return sequence;
    }

    public long scheduleAfter(long delay, Runnable action) {
        if (delay < 0) throw new IllegalArgumentException("Delay must not be negative");
        return scheduleAt(Math.addExact(now, delay), action);
    }

    public boolean runNext() {
        ScheduledEvent event = events.poll();
        if (event == null) return false;
        now = event.virtualTime();
        event.action().run();
        return true;
    }

    public int runUntilIdle(int maxEvents) {
        if (maxEvents < 0) throw new IllegalArgumentException("Maximum must not be negative");
        int executed = 0;
        while (executed < maxEvents && runNext()) executed++;
        return executed;
    }

    public int pendingEvents() {
        return events.size();
    }

    private record ScheduledEvent(long virtualTime, long sequence, Runnable action)
            implements Comparable<ScheduledEvent> {
        @Override
        public int compareTo(ScheduledEvent other) {
            int timeComparison = Long.compare(virtualTime, other.virtualTime);
            return timeComparison != 0 ? timeComparison : Long.compare(sequence, other.sequence);
        }
    }
}
