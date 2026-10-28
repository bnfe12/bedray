package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import java.util.Arrays;

/** Shared CPU identity rules for reference and native scene captures. */
final class SectionGeometryIdentity {
    private SectionGeometryIdentity() {}

    static boolean same(DecodedSection a, DecodedSection b) {
        if (a.totalQuads() != b.totalQuads()) return false;
        for (int pass = 0; pass < DecodedSection.PASS_COUNT; pass++) {
            if (a.quadsInPass(pass) != b.quadsInPass(pass)) return false;
        }
        return Arrays.equals(a.rawWords, b.rawWords);
    }

    static long lightHash(int[] words, int[] quadFlags, int count) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < count; i++) {
            int word = words[i] | (quadFlags == null ? 0 : quadFlags[i >> 2]);
            hash = (hash ^ (word & 0xFFFFFFFFL)) * 0x100000001b3L;
        }
        return hash;
    }
}
