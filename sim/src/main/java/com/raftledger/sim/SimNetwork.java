package com.raftledger.sim;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

public final class SimNetwork<T> {
    private final RandomGenerator random;
    private final Set<Link> blockedLinks = new HashSet<>();
    private double dropProbability;
    private double duplicateProbability;
    private long minimumDelay;
    private long maximumDelay;
    private long sequence;

    public SimNetwork(RandomGenerator random) {
        this.random = random;
    }

    public void setDropProbability(double probability) {
        requireProbability(probability);
        dropProbability = probability;
    }

    public void setDuplicateProbability(double probability) {
        requireProbability(probability);
        duplicateProbability = probability;
    }

    public void setDelayRange(long minimumDelay, long maximumDelay) {
        if (minimumDelay < 0 || maximumDelay < minimumDelay) {
            throw new IllegalArgumentException("Invalid delay range");
        }
        this.minimumDelay = minimumDelay;
        this.maximumDelay = maximumDelay;
    }

    public void block(int from, int to) {
        blockedLinks.add(new Link(from, to));
    }

    public void partition(Set<Integer> left, Set<Integer> right) {
        for (int from : left) {
            for (int to : right) {
                block(from, to);
                block(to, from);
            }
        }
    }

    public void heal() {
        blockedLinks.clear();
    }

    public List<Delivery<T>> send(long now, int from, int to, T payload) {
        if (blockedLinks.contains(new Link(from, to))
                || random.nextDouble() < dropProbability) {
            return List.of();
        }
        List<Delivery<T>> deliveries = new ArrayList<>();
        deliveries.add(delivery(now, from, to, payload));
        if (random.nextDouble() < duplicateProbability) {
            deliveries.add(delivery(now, from, to, payload));
        }
        return List.copyOf(deliveries);
    }

    private Delivery<T> delivery(long now, int from, int to, T payload) {
        long delay = minimumDelay == maximumDelay
                ? minimumDelay
                : random.nextLong(minimumDelay, Math.addExact(maximumDelay, 1));
        return new Delivery<>(Math.addExact(now, delay), sequence++, from, to, payload);
    }

    private static void requireProbability(double value) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Probability must be between zero and one");
        }
    }

    public record Delivery<T>(long virtualTime, long sequence, int from, int to, T payload) {}

    private record Link(int from, int to) {}
}
