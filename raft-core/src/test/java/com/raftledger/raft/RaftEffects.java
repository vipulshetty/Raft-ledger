package com.raftledger.raft;

import java.util.List;

/** Test-only helpers for filtering and locating effects returned by step(). */
final class RaftEffects {

    private RaftEffects() {}

    static List<Effect.Persist> persists(List<Effect> effects) {
        return effects.stream()
                .filter(Effect.Persist.class::isInstance)
                .map(Effect.Persist.class::cast)
                .toList();
    }

    static List<Effect.Send> sends(List<Effect> effects) {
        return effects.stream()
                .filter(Effect.Send.class::isInstance)
                .map(Effect.Send.class::cast)
                .toList();
    }

    static List<Effect.Apply> applies(List<Effect> effects) {
        return effects.stream()
                .filter(Effect.Apply.class::isInstance)
                .map(Effect.Apply.class::cast)
                .toList();
    }

    static List<Effect.Reply> replies(List<Effect> effects) {
        return effects.stream()
                .filter(Effect.Reply.class::isInstance)
                .map(Effect.Reply.class::cast)
                .toList();
    }

    /** Index of the first Persist effect, or -1 when absent. */
    static int firstPersistIndex(List<Effect> effects) {
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i) instanceof Effect.Persist) return i;
        }
        return -1;
    }

    /**
     * Index of the first Persist effect whose durable state satisfies {@code matcher},
     * or -1 when absent. Needed because "persist index &lt; send index" is vacuously
     * true (-1 &lt; 0) when the node emitted no Persist effect at all.
     */
    static int firstPersistIndex(List<Effect> effects, java.util.function.Predicate<PersistentState> matcher) {
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i) instanceof Effect.Persist persist
                    && matcher.test(persist.state())) return i;
        }
        return -1;
    }

    /** Index of the first Send effect, or -1 when absent. */
    static int firstSendIndex(List<Effect> effects) {
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i) instanceof Effect.Send) return i;
        }
        return -1;
    }
}
