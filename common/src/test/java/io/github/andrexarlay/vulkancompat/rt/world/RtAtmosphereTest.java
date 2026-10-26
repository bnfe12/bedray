package io.github.andrexarlay.vulkancompat.rt.world;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

/**
 * L1/L2 checks of the atmosphere post passes (light shafts, bloom, sun glare): all shader stages compile with shaderc, the settings
 * clamp and round-trip, the UI option table is consistent with get/set, the size helpers of the passes and the weather gate of the
 * glare. No GPU involved.
 */
public final class RtAtmosphereTest {
    private RtAtmosphereTest() {
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError(what);
        }
    }

    public static void runAll() {
        shadersCompile();
        settings();
        helpers();
        glare();
        fogMath();
    }

    /** The sun halo of a frame is a pure product of RtSky; the weather must be able to switch it off completely. */
    static void glare() {
        check(RtSky.glareStrength(1f, 1f, 1f, 1f) == 1f, "full glare at noon under a clear sky");
        check(RtSky.glareStrength(1f, 1f, 1f, 0f) == 0f, "rain hides the sun, so its halo goes with it");
        check(RtSky.glareStrength(1f, 0f, 1f, 1f) == 0f, "night has no sun glare");
        check(RtSky.glareStrength(1f, 1f, 0.5f, 1f) == 0.5f, "the elevation ramp is smoothstep(0,1,e)");
        check(RtSky.glareStrength(2f, 1f, 1f, 1f) == 2f, "the settings strength scales the term");
        check(RtSky.glareStrength(1f, 1f, -1f, 1f) == 0f && RtSky.glareStrength(1f, 3f, 3f, 5f) == 1f, "every factor clamps");
    }

    static void shadersCompile() {
        String post = "#define VKB_POST 1" + (char) 10 + "#define VKB_ACCUM 1" + (char) 10;
        for (int stage = 0; stage < 2; stage++) {
            ByteBuffer spv = RtShaderCompiler.compileResource("/shaders/rt_volumetric.comp", "rt_volumetric.comp+stage" + stage,
                post + "#define VOL_STAGE " + stage + (char) 10);
            check(spv.remaining() > 1000 && (spv.remaining() & 3) == 0, "rt_volumetric stage " + stage + " SPIR-V");
        }
        for (int stage = 0; stage < 4; stage++) {
            ByteBuffer spv = RtShaderCompiler.compileResource("/shaders/rt_bloom.comp", "rt_bloom.comp+stage" + stage,
                "#define BLOOM_STAGE " + stage + (char) 10);
            check(spv.remaining() > 500 && (spv.remaining() & 3) == 0, "rt_bloom stage " + stage + " SPIR-V");
        }
        // the main shaders still compile with the VKB_POST guard in rt_common.glsl (no define = unchanged declarations)
        check(RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp").remaining() > 1000, "rt_rays still compiles");
    }

    static void settings() {
        double[] saved = new double[RtAtmosphereSettings.options().size()];
        int i = 0;
        for (RtAtmosphereSettings.Option o : RtAtmosphereSettings.options()) {
            saved[i++] = RtAtmosphereSettings.get(o.key());
        }
        try {
            Set<String> keys = new HashSet<>();
            for (RtAtmosphereSettings.Option o : RtAtmosphereSettings.options()) {
                check(keys.add(o.key()), "unique option key " + o.key());
                check(!Double.isNaN(RtAtmosphereSettings.get(o.key())), "get(" + o.key() + ") known");
                check(o.description() != null && o.description().length() > 10 && o.label() != null, "text of " + o.key());
                check(o.def() >= o.min() && o.def() <= o.max(), "default inside range of " + o.key());
                RtAtmosphereSettings.set(o.key(), o.max() + 1000);
                check(RtAtmosphereSettings.get(o.key()) <= o.max() + 1e-6, "clamped high " + o.key());
                RtAtmosphereSettings.set(o.key(), o.min() - 1000);
                check(RtAtmosphereSettings.get(o.key()) >= o.min() - 1e-6, "clamped low " + o.key());
                if (o.kind() == RtAtmosphereSettings.Kind.CHOICE) {
                    check(o.labels().size() == (int) o.max() + 1, "choice labels of " + o.key());
                }
            }
            check(!RtAtmosphereSettings.set("nope", 1), "unknown key rejected");
            check(!RtAtmosphereSettings.set("bloom_strength", Double.NaN), "NaN rejected");
            RtAtmosphereSettings.set("godrays", 0);
            check(RtAtmosphereSettings.godRays() == RtAtmosphereSettings.GodRays.OFF, "godrays off");
            RtAtmosphereSettings.set("bloom", 1);
            RtAtmosphereSettings.set("bloom_strength", 0);
            check(!RtAtmosphereSettings.bloom(), "bloom with strength 0 is off");
            check(RtAtmosphereSettings.stepsFor(0) == 6 && RtAtmosphereSettings.stepsFor(1) == 8
                && RtAtmosphereSettings.stepsFor(2) == 12 && RtAtmosphereSettings.stepsFor(3) == 16
                && RtAtmosphereSettings.stepsFor(99) == 16 && RtAtmosphereSettings.stepsFor(-5) == 6, "steps per quality");
            check(RtAtmosphereSettings.parseGodRays("inline", RtAtmosphereSettings.GodRays.POST) == RtAtmosphereSettings.GodRays.INLINE, "parse inline");
            check(RtAtmosphereSettings.parseGodRays("Off", RtAtmosphereSettings.GodRays.POST) == RtAtmosphereSettings.GodRays.OFF, "parse off");
            check(RtAtmosphereSettings.parseGodRays("garbage", RtAtmosphereSettings.GodRays.POST) == RtAtmosphereSettings.GodRays.POST, "parse default");
        } finally {
            i = 0;
            for (RtAtmosphereSettings.Option o : RtAtmosphereSettings.options()) {
                RtAtmosphereSettings.set(o.key(), saved[i++]);
            }
        }
    }

    static void helpers() {
        check(RtVolumetricPass.halfSize(1920) == 960 && RtVolumetricPass.halfSize(1081) == 541 && RtVolumetricPass.halfSize(1) == 1, "half size");
        check(RtVolumetricPass.consecutive(10, 11) && !RtVolumetricPass.consecutive(10, 12) && !RtVolumetricPass.consecutive(Long.MIN_VALUE, 0),
            "consecutive frames");
        check(RtBloomPass.mipSize(1920, 0) == 960 && RtBloomPass.mipSize(1920, 5) == 30 && RtBloomPass.mipSize(1, 5) == 1
            && RtBloomPass.mipSize(1081, 0) == 541, "bloom mip sizes");
        for (int s : new int[] {1, 7, 640, 1080, 3840}) {
            int prev = s;
            for (int m = 0; m < RtBloomPass.LEVELS; m++) {
                int cur = RtBloomPass.mipSize(s, m);
                check(cur >= 1 && cur <= (prev + 1) / 2 + (m == 0 ? 0 : 0) + 1, "mip chain shrinks for " + s);
                prev = cur;
            }
        }
    }

    static void fogMath() {
        check(RtFogState.linear(5, 10, 20) == 0f && RtFogState.linear(25, 10, 20) == 1f && Math.abs(RtFogState.linear(15, 10, 20) - 0.5f) < 1e-6f,
            "linear fog");
        check(RtFogState.linear(1, 5, 5) == 0f && RtFogState.linear(6, 5, 5) == 1f, "degenerate interval does not divide by zero");
        check(RtFogState.renderDistanceStart(192f) == 192f - 19.2f, "render distance fog 12 chunks: end/10");
        check(RtFogState.renderDistanceStart(32f) == 28f && RtFogState.renderDistanceStart(1024f) == 960f, "render distance fog clamp 4..64");
        check(RtFogState.rainStart(0f, 1f) == -160f && RtFogState.rainStart(10f, 0f) == 10f, "rain start");
        check(RtFogState.rainEnd(1000f, 1f) == 744f && RtFogState.rainEnd(1000f, 0f) == 1000f && RtFogState.rainEnd(200f, 1f) == 96f
            && RtFogState.rainEnd(50f, 1f) == 50f, "rain end never closer than min(96, end)");
        // clear day, render distance 12 chunks: the world edge is fully fogged, 100 blocks in front of it nothing yet
        float edge = RtFogState.transmittance(192f, 192f, 0f, 1.0e6f, 172.8f, 192f, 1f);
        float near = RtFogState.transmittance(90f, 90f, 0f, 1.0e6f, 172.8f, 192f, 1f);
        check(edge == 0f && near > 0.999f, "fog hides the world edge and leaves the near terrain");
        check(RtFogState.transmittance(192f, 192f, 0f, 1.0e6f, 172.8f, 192f, 0f) == 1f, "weight 0 = no vanilla fog");
        float cylVertical = RtFogState.transmittance(10f, 200f, 0f, 1.0e6f, 172.8f, 192f, 1f);
        check(cylVertical == 0f, "cylindrical distance (height difference) fogs too");
    }
}
