package io.github.andrexarlay.vulkancompat.rt.sodium;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Conservative extra filtering after Sodium's own face culling; operates only on confirmed full glass-block boundaries. */
public final class GlassFaceFilter {
    @FunctionalInterface public interface Lookup { int glassProfile(float u, float v); }
    private static volatile Lookup lookup;
    private static volatile boolean enabled = true;
    private static final AtomicLong examined = new AtomicLong();
    private static final AtomicLong removed = new AtomicLong();

    public static void setLookup(Lookup value) { lookup = value; }
    public static void setEnabled(boolean value) { enabled = value; }
    public static long examinedFaces() { return examined.get(); }
    public static long removedFaces() { return removed.get(); }

    private record Key(long a, long b, long c, long d, int material, long uv, int color, int light, int pass) {
        Key shifted(long delta) { return new Key(a + delta, b + delta, c + delta, d + delta, material, uv, color, light, pass); }
    }
    private record Face(int quad, int axis, int sign) {}

    public static DecodedSection apply(DecodedSection section) {
        Lookup atlas = lookup;
        if (!enabled || atlas == null || section.rawWords == null || section.totalQuads() == 0) return section;
        Map<Key, ArrayList<Face>> faces = new HashMap<>();
        int start = section.passQuadStart(DecodedSection.PASS_CUTOUT);
        int translucentStart = section.passQuadStart(DecodedSection.PASS_TRANSLUCENT);
        long examinedHere = 0;
        for (int q = start; q < section.totalQuads(); q++) {
            float centerU = 0f, centerV = 0f;
            for (int v = 0; v < 4; v++) {
                centerU += section.uvs[q * 8 + v * 2];
                centerV += section.uvs[q * 8 + v * 2 + 1];
            }
            int material = atlas.glassProfile(centerU * 0.25f, centerV * 0.25f);
            if (material == 0) continue; // no per-face temporary arrays or hash entries for the rest of the terrain
            int axis = -1;
            for (int a = 0; a < 3; a++) if (Math.abs(section.normals[q * 3 + a]) > 0.9999f) axis = a;
            if (axis < 0) continue;
            float[] lo = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
            float[] hi = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
            int minU = 32768, minV = 32768, maxU = 0, maxV = 0;
            int color = section.colors[q * 4], light = section.light[q * 4];
            boolean uniform = true;
            long[] corners = new long[4];
            for (int v = 0; v < 4; v++) {
                int vi = q * 4 + v;
                uniform &= section.colors[vi] == color && section.light[vi] == light;
                int raw = vi * 5;
                int w0 = section.rawWords[raw], w1 = section.rawWords[raw + 1], uv = section.rawWords[raw + 3];
                corners[v] = ((long) SodiumMeshDecoder.quantizedPosition(w0, w1, 0) << 40)
                    | ((long) SodiumMeshDecoder.quantizedPosition(w0, w1, 1) << 20)
                    | SodiumMeshDecoder.quantizedPosition(w0, w1, 2);
                minU = Math.min(minU, uv & 32767); maxU = Math.max(maxU, uv & 32767);
                minV = Math.min(minV, (uv >>> 16) & 32767); maxV = Math.max(maxV, (uv >>> 16) & 32767);
                for (int a = 0; a < 3; a++) {
                    float p = section.positions[vi * 3 + a];
                    lo[a] = Math.min(lo[a], p); hi[a] = Math.max(hi[a], p);
                }
            }
            if (!uniform || Math.abs(hi[axis] - lo[axis]) > 0.0001f
                || Math.abs(lo[axis] - Math.rint(lo[axis])) > 0.0001) continue;
            boolean unit = true;
            for (int a = 0; a < 3; a++) if (a != axis)
                unit &= Math.abs(hi[a] - lo[a] - 1f) < 0.0001f && Math.abs(lo[a] - Math.rint(lo[a])) < 0.0001;
            if (!unit) continue; // panes, sheets with fractional bounds, and custom/slanted meshes remain untouched
            examinedHere++;
            Arrays.sort(corners);
            long uv = (long) minU | (long) maxU << 15 | (long) minV << 30 | (long) maxV << 45;
            int pass = q < translucentStart ? DecodedSection.PASS_CUTOUT : DecodedSection.PASS_TRANSLUCENT;
            Key key = new Key(corners[0], corners[1], corners[2], corners[3], material, uv, color, light, pass);
            var group = faces.computeIfAbsent(key, ignored -> new ArrayList<>(2));
            if (group.size() < 3) group.add(new Face(q, axis, section.normals[q * 3 + axis] > 0 ? 1 : -1));
        }
        examined.addAndGet(examinedHere);
        if (faces.isEmpty()) return section;
        boolean[] discard = new boolean[section.totalQuads()];
        int count = 0;
        for (var entry : faces.entrySet()) {
            var pair = entry.getValue();
            if (pair.size() != 2 || pair.get(0).sign == pair.get(1).sign) continue;
            Face face = pair.get(0);
            long delta = 1L << ((2 - face.axis) * 20 + 15); // one block in the quantized coordinate
            // A coincident double-sided sheet alone is insufficient: require outward companion faces of both adjacent cubes.
            if (!outward(faces.get(entry.getKey().shifted(-delta)), -1)
                || !outward(faces.get(entry.getKey().shifted(delta)), 1)) continue;
            discard[pair.get(0).quad] = true;
            discard[pair.get(1).quad] = true;
            count += 2;
        }
        if (count == 0) return section;
        removed.addAndGet(count);
        int size = section.totalQuads() - count;
        float[] pos = new float[size * 12], uv = new float[size * 8], normals = new float[size * 3];
        int[] colors = new int[size * 4], light = new int[size * 4], raw = new int[size * 20], passes = new int[3];
        int dst = 0;
        for (int pass = 0; pass < 3; pass++) {
            int from = section.passQuadStart(pass), end = from + section.quadsInPass(pass);
            for (int q = from; q < end; q++) if (!discard[q]) {
                System.arraycopy(section.positions, q * 12, pos, dst * 12, 12);
                System.arraycopy(section.uvs, q * 8, uv, dst * 8, 8);
                System.arraycopy(section.normals, q * 3, normals, dst * 3, 3);
                System.arraycopy(section.colors, q * 4, colors, dst * 4, 4);
                System.arraycopy(section.light, q * 4, light, dst * 4, 4);
                System.arraycopy(section.rawWords, q * 20, raw, dst * 20, 20);
                passes[pass]++; dst++;
            }
        }
        return new DecodedSection(section.key, section.sectionX, section.sectionY, section.sectionZ, passes, pos, uv,
            colors, light, normals, section.posMin, section.posMax, raw);
    }

    private static boolean outward(ArrayList<Face> faces, int sign) {
        return faces != null && faces.size() == 1 && faces.get(0).sign == sign;
    }
}
