package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import java.util.Arrays;

/** CPU identity rules for native scene captures. */
final class SectionGeometryIdentity {
    private SectionGeometryIdentity() {}

    static boolean same(DecodedSection a, DecodedSection b) {
        if (a.totalQuads() != b.totalQuads()) return false;
        for (int pass = 0; pass < DecodedSection.PASS_COUNT; pass++) {
            if (a.quadsInPass(pass) != b.quadsInPass(pass)) return false;
        }
        return Arrays.equals(a.rawWords, b.rawWords);
    }

    static long tagHash(int[] quadFlags, int count) {
        if (quadFlags == null) return 0;
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < count; i++) {
            hash = (hash ^ quadFlags[i]) * 0x100000001b3L;
        }
        return hash;
    }
}
