package com.raftledger.simtest;

import com.raftledger.sim.SimDisk;
import com.raftledger.sim.SimNetwork;
import com.raftledger.sim.VirtualClock;
import com.raftledger.sim.World;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.io.BufferedReader;
import java.io.InputStreamReader;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorTest {
    @Test
    void virtualClockOrdersByTimeThenInsertionSequence() {
        VirtualClock clock = new VirtualClock();
        List<String> order = new ArrayList<>();
        clock.scheduleAt(10, () -> order.add("first"));
        clock.scheduleAt(10, () -> order.add("second"));
        clock.scheduleAt(5, () -> order.add("earlier"));

        assertThat(clock.runUntilIdle(10)).isEqualTo(3);
        assertThat(order).containsExactly("earlier", "first", "second");
        assertThat(clock.now()).isEqualTo(10);
    }

    @Test
    void dropAndPartitionPreventDeliveryAndHealingRestoresIt() {
        SimNetwork<String> network = new SimNetwork<>(
                java.util.random.RandomGeneratorFactory
                        .<java.util.random.RandomGenerator>of("L64X128MixRandom").create(1L));
        network.setDropProbability(1.0);
        assertThat(network.send(0, 0, 1, "dropped")).isEmpty();

        network.setDropProbability(0.0);
        network.partition(Set.of(0), Set.of(1));
        assertThat(network.send(0, 0, 1, "blocked")).isEmpty();
        network.block(1, 0);
        assertThat(network.send(0, 1, 0, "asymmetric-blocked")).isEmpty();
        network.heal();
        assertThat(network.send(0, 0, 1, "delivered")).hasSize(1);
    }

    @Test
    void duplicateAndDelayAreObservableAndOrderedByDeliveryMetadata() {
        SimNetwork<String> network = new SimNetwork<>(
                java.util.random.RandomGeneratorFactory
                        .<java.util.random.RandomGenerator>of("L64X128MixRandom").create(2L));
        network.setDuplicateProbability(1.0);
        network.setDelayRange(3, 3);

        List<SimNetwork.Delivery<String>> deliveries = network.send(10, 0, 1, "message");

        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).extracting(SimNetwork.Delivery::virtualTime)
                .containsOnly(13L);
        assertThat(deliveries.get(0).sequence()).isLessThan(deliveries.get(1).sequence());
    }

    @Test
    void diskCrashKeepsSyncedDataAndDropsUnsyncedData() {
        SimDisk disk = new SimDisk();
        disk.write("synced", "one".getBytes(StandardCharsets.UTF_8));
        disk.fsync("synced");
        disk.write("lost", "two".getBytes(StandardCharsets.UTF_8));
        disk.crash();

        assertThat(new String(disk.read("synced"), StandardCharsets.UTF_8)).isEqualTo("one");
        assertThat(disk.read("lost")).isNull();
    }

    @Test
    void tornUnsyncedWriteIsRetainedUntilCrashThenDiscarded() {
        SimDisk disk = new SimDisk();
        disk.write("record", "abcdef".getBytes(StandardCharsets.UTF_8));
        disk.tearLastWrite("record", 3);

        assertThat(new String(disk.read("record"), StandardCharsets.UTF_8)).isEqualTo("abc");
        disk.crash();
        assertThat(disk.read("record")).isNull();
    }

    @Test
    void clockLimitsExecutionAndRejectsPastScheduling() {
        VirtualClock clock = new VirtualClock();
        clock.scheduleAt(2, () -> {});
        clock.scheduleAt(3, () -> {});

        assertThat(clock.runUntilIdle(1)).isEqualTo(1);
        assertThat(clock.pendingEvents()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> clock.scheduleAt(1, () -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Property
    void sameSeedProducesTheSameTraceHash(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        assertThat(new World().run(seed, 25).traceHash())
                .isEqualTo(new World().run(seed, 25).traceHash());
    }

    @Property
    void differentSeedsProduceDifferentTraceHashes(
            @ForAll @LongRange(min = 0, max = 100_000) long firstSeed) {
        assertThat(new World().run(firstSeed, 25).traceHash())
                .isNotEqualTo(new World().run(firstSeed + 1, 25).traceHash());
    }

    @Test
    void brokenToyProtocolIsCaught() {
        World.RunResult broken = new World(true).run(7L, 10);
        World.RunResult healthy = new World().run(7L, 10);

        assertThat(broken.invariantViolation()).isTrue();
        assertThat(broken.failureReason()).contains("replica counters diverged");
        assertThat(broken.failureStep()).isGreaterThanOrEqualTo(0);
        assertThat(broken.trace()).isNotEmpty();
        assertThat(healthy.invariantViolation()).isFalse();
        assertThat(healthy.failureReason()).isNull();
        assertThat(healthy.trace()).hasSize(10);
    }

    @Test
    void sameSeedMatchesInSeparateJvm() throws Exception {
        String javaExecutable = System.getProperty("java.home")
                + java.io.File.separator + "bin"
                + java.io.File.separator + "java";
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                SimulatorChildMain.class.getName(),
                "42",
                "25").redirectErrorStream(true).start();
        String childHash;
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            childHash = output.readLine();
        }
        assertThat(process.waitFor()).isZero();
        assertThat(childHash).isEqualTo(new World().run(42L, 25).traceHash());
    }
}
