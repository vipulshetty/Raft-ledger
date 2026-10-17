package com.raftledger.sim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

public final class World {
    private final boolean brokenVariant;

    public World() {
        this(false);
    }

    public World(boolean brokenVariant) {
        this.brokenVariant = brokenVariant;
    }

    public RunResult run(long seed, int maxSteps) {
        if (maxSteps < 0) throw new IllegalArgumentException("Maximum steps must not be negative");
        RandomGenerator random = RandomGeneratorFactory
                .<RandomGenerator>of("L64X128MixRandom").create(seed);
        List<String> trace = new ArrayList<>();
        int[] counters = new int[3];
        boolean violation = false;
        for (int step = 0; step < maxSteps; step++) {
            int amount = random.nextInt(1, 4);
            int target = random.nextInt(counters.length);
            counters[target] += amount;
            trace.add(step + ":increment:" + target + ":" + amount);
            if (brokenVariant) {
                counters[target] += amount;
                trace.add(step + ":duplicate:" + target + ":" + amount);
                violation = counters[target] != counters[0]
                        || counters[target] != counters[1]
                        || counters[target] != counters[2];
            } else {
                for (int i = 0; i < counters.length; i++) counters[i] += amount;
            }
        }
        return new RunResult(seed, maxSteps, traceHash(trace), violation, List.copyOf(trace));
    }

    private static String traceHash(List<String> trace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\n", trace).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                result.append(Character.forDigit((value >>> 4) & 0xf, 16))
                        .append(Character.forDigit(value & 0xf, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the JDK", exception);
        }
    }

    public record RunResult(
            long seed,
            int steps,
            String traceHash,
            boolean invariantViolation,
            List<String> trace
    ) {}
}
