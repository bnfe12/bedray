package io.github.andrexarlay.vulkancompat.rt.particles;

import java.util.BitSet;

/**
 * Which prepared particle groups of the frame were already captured. The game executes a group once in the classic path but
 * once per OIT stage (depth bounds, transmittance, accumulate) in the improved-transparency path; the layer replay needs each
 * group exactly once, in the order the game first executed it (solid groups before translucent ones).
 */
final class ParticleGroupLedger {
    private final BitSet seen = new BitSet();
    private int order;

    /** True the first time {@code groupIndex} is offered in this frame. */
    boolean firstVisit(int groupIndex) {
        if (groupIndex < 0 || seen.get(groupIndex)) {
            return false;
        }
        seen.set(groupIndex);
        order++;
        return true;
    }

    int groups() {
        return order;
    }

    void clear() {
        seen.clear();
        order = 0;
    }
}
