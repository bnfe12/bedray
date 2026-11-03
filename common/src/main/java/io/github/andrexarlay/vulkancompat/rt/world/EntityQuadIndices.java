package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.IntBuffer;

/** Bounded index packing for the staged-quad capture path. */
final class EntityQuadIndices {
    private EntityQuadIndices() {}
    static void write(IntBuffer target, int quads) {
        if (quads < 0 || (long) quads * 6 > target.remaining())
            throw new IllegalArgumentException("quad indices exceed the mapped range");
        for (int i = 0; i < quads; i++) {
            int base = Math.multiplyExact(i, 4);
            target.put(base).put(base + 1).put(base + 2).put(base + 2).put(base + 3).put(base);
        }
    }
}
