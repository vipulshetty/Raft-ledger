package com.raftledger.simtest;

import com.raftledger.sim.World;

public final class SimulatorChildMain {
    private SimulatorChildMain() {}

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("Expected seed and step count");
        System.out.println(new World().run(
                Long.parseLong(args[0]), Integer.parseInt(args[1])).traceHash());
    }
}
