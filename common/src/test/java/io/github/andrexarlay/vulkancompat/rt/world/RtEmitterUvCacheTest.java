package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RtEmitterUvCacheTest {
    private static final class Texture implements RtEmitters.TexelEmission {
        int reads;
        boolean redstone;
        float strength = 1;
        public int width() { return 16; }
        public int height() { return 16; }
        public float at(int x, int y) { reads++; return x > 3 && x < 9 && y > 5 ? strength : 0; }
        public boolean isRedstoneAt(int x, int y) { return redstone; }
    }
    private static float[] geometry(float size) {
        return new float[]{2,3,4, 2+size,3,4, 2+size,3,4+size, 2,3,4+size};
    }
    private static void equalRecords(RtEmitters.QuadEmissionCache cache, Texture tex, float[] uv, float size,
                                     boolean open, float signal) {
        float[] cached = new float[RtEmitters.FLOATS], reference = cached.clone();
        boolean expected = RtEmitters.buildQuadRecord(reference, 0, geometry(size), 0, uv, 0, tex, open, signal, null);
        assertEquals(expected, RtEmitters.buildQuadRecord(cached, 0, geometry(size), 0, uv, 0, tex, open, signal, cache));
        assertArrayEquals(reference, cached, 0f);
    }
    @Test void cachedUvStatisticsPreserveGeometryPowerSidednessAndRotatedSubrects() {
        var tex = new Texture();
        var cache = new RtEmitters.QuadEmissionCache();
        cache.bind(tex, 1, 16, 16);
        for (float[] uv : new float[][]{{0,0, 1,0, 1,1, 0,1}, {1,0, 1,1, 0,1, 0,0},
                                       {0.25f,0.25f, 0.75f,0.25f, 0.75f,1, 0.25f,1}})
            for (float size : new float[]{0.1f,1,3}) for (boolean open : new boolean[]{false,true})
                equalRecords(cache, tex, uv, size, open, 1);
    }
    @Test void reuseSkipsTexelReadsAndRevisionInvalidatesAnInPlaceAtlas() {
        var tex = new Texture();
        var cache = new RtEmitters.QuadEmissionCache();
        float[] uv = {0,0, 1,0, 1,1, 0,1};
        cache.bind(tex, 1, 16, 16);
        float[] out = new float[RtEmitters.FLOATS];
        assertTrue(RtEmitters.buildQuadRecord(out,0,geometry(1),0,uv,0,tex,false,1,cache));
        int reads = tex.reads;
        assertTrue(RtEmitters.buildQuadRecord(out,0,geometry(3),0,uv,0,tex,true,1,cache));
        assertEquals(reads, tex.reads);
        tex.strength = 0;
        cache.bind(tex, 2, 16, 16);
        assertFalse(RtEmitters.buildQuadRecord(out,0,geometry(1),0,uv,0,tex,false,1,cache));
        assertTrue(tex.reads > reads);
    }
    @Test void redstoneSignalAndCacheSaturationCannotChangeTheRecord() {
        var tex = new Texture();
        var cache = new RtEmitters.QuadEmissionCache();
        cache.bind(tex,1,16,16);
        tex.redstone = true;
        float[] uv = {0,0, 1,0, 1,1, 0,1};
        for (float signal : new float[]{0,0.2f,0.8f,0}) equalRecords(cache,tex,uv,1,false,signal);
        tex.redstone = false;
        cache.bind(tex,2,16,16);
        for (int i=0; i<4100; ++i) {
            float shift = i * 0.00001f;
            equalRecords(cache,tex,new float[]{shift,0, 1,0, 1,1, shift,1},1,false,1);
        }
    }
}
