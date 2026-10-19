package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rt.world.RtPipeline;
import io.github.andrexarlay.vulkancompat.rt.world.RtShaderCompiler;
import org.joml.Matrix4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * L2 checks of the NRD path: struct layouts, mode selection, availability, packing math, camera math, shader compilation and
 * (when a locally built vkb_nrd.dll is present) the real FFM binding. No GPU, no game: NOT evidence that denoising works.
 */
public final class NrdChecks {
    private NrdChecks() {
    }

    public static void runAll() {
        layouts();
        frameSettingsWrite();
        reblurSettingsWrite();
        modeSelection();
        versionSupport();
        availability();
        octEncoding();
        packEncodings();
        reblurPacking();
        relaxPacking();
        cameraMath();
        shaderDefines();
        shadersCompile();
        nativeBinding();
    }

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void near(float expected, float actual, float eps, String msg) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(msg + ": expected " + expected + " actual " + actual);
        }
    }

    private static void layouts() {
        NrdLayouts.verify();
        // the C side static_asserts the same numbers (vkb_nrd.cpp:54-60)
        check(NrdLayouts.LIBRARY_INFO_SIZE == 44 && NrdLayouts.RESOURCE_SIZE == 24 && NrdLayouts.CREATE_INFO_SIZE == 24
            && NrdLayouts.FRAME_SETTINGS_SIZE == 344 && NrdLayouts.REBLUR_SETTINGS_SIZE == 52 && NrdLayouts.SIGMA_SETTINGS_SIZE == 24
            && NrdLayouts.DISPATCH_STATS_SIZE == 40, "struct sizes equal the C static_asserts");
        check(NrdLayouts.RES_COUNT == 32, "VKB_NRD_RES_COUNT");
        check(NrdLayouts.RES_IN_NORMAL_ROUGHNESS == 1 && NrdLayouts.RES_IN_VIEWZ == 2 && NrdLayouts.RES_OUT_SPEC_RADIANCE_HITDIST == 19,
            "resource slots of vkb_nrd.h");
    }

    private static void frameSettingsWrite() {
        NrdFrameSettings f = new NrdFrameSettings();
        f.frameIndex = 7;
        f.accumulationMode = NrdLayouts.ACCUM_RESTART;
        f.flags = NrdLayouts.FRAME_MV_WORLD_SPACE | NrdLayouts.FRAME_ENABLE_VALIDATION;
        f.viewToClip.identity().m00(2f).m31(5f);   // column-major: m31 = column 3, row 1 -> element 13
        f.worldToView.identity().m30(9f);          // element 12
        f.mvScale[2] = 0.5f;
        f.jitter[1] = 0.25f;
        f.rectWidth = 1280;
        f.rectHeight = 720;
        f.rectWidthPrev = 1279;
        f.rectHeightPrev = 719;
        f.viewZScale = 1f;
        f.denoisingRange = 4096f;
        f.disocclusionThreshold = 0.01f;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(NrdLayouts.FRAME_SETTINGS_SIZE, 8);
            f.write(s);
            check(s.get(JAVA_INT, 0) == 344, "struct_size");
            check(s.get(JAVA_INT, 4) == 7 && s.get(JAVA_INT, 8) == 1 && s.get(JAVA_INT, 12) == 3, "frame index / accumulation / flags");
            near(2f, s.get(JAVA_FLOAT, 16), 0f, "view_to_clip[0]");
            near(5f, s.get(JAVA_FLOAT, 16 + 13 * 4), 0f, "view_to_clip[13] (column-major)");
            near(9f, s.get(JAVA_FLOAT, 144 + 12 * 4), 0f, "world_to_view[12]");
            near(0.5f, s.get(JAVA_FLOAT, 272 + 8), 0f, "mv_scale[2]");
            near(0.25f, s.get(JAVA_FLOAT, 296 + 4), 0f, "jitter[1]");
            check(s.get(JAVA_INT, 312) == 1280 && s.get(JAVA_INT, 316) == 720 && s.get(JAVA_INT, 320) == 1279 && s.get(JAVA_INT, 324) == 719,
                "rect sizes");
            near(1f, s.get(JAVA_FLOAT, 328), 0f, "view_z_scale");
            near(4096f, s.get(JAVA_FLOAT, 336), 0f, "denoising_range");
            near(0.01f, s.get(JAVA_FLOAT, 340), 0f, "disocclusion_threshold");
            boolean threw = false;
            try {
                f.write(a.allocate(100, 8));
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "too small segment rejected");
        }
    }

    private static void reblurSettingsWrite() {
        NrdReblurParams p = NrdReblurParams.defaults();
        check(p.hitDistA() == 3f && p.hitDistB() == 0.1f && p.hitDistC() == 20f, "REBLUR default hit distance parameters 3 / 0.1 / 20");
        try (Arena a = Arena.ofConfined()) {
            MemorySegment s = a.allocate(NrdLayouts.REBLUR_SETTINGS_SIZE, 8);
            p.write(s);
            check(s.get(JAVA_INT, 0) == 52 && s.get(JAVA_INT, 4) == 30 && s.get(JAVA_INT, 8) == 6 && s.get(JAVA_INT, 12) == 63, "frame counts");
            near(3f, s.get(JAVA_FLOAT, 16), 0f, "A");
            near(20f, s.get(JAVA_FLOAT, 24), 0f, "C");
            near(0.02f, s.get(JAVA_FLOAT, 44), 0f, "plane distance sensitivity");
            check(s.get(JAVA_INT, 48) == 1, "anti firefly");
        }
    }

    private static void modeSelection() {
        check(DenoiserMode.parse(null) == DenoiserMode.OFF && DenoiserMode.parse("") == DenoiserMode.OFF, "unset is OFF (default)");
        check(DenoiserMode.parse("off") == DenoiserMode.OFF && DenoiserMode.parse("OFF") == DenoiserMode.OFF, "off");
        check(DenoiserMode.parse("nrd") == DenoiserMode.NRD_REBLUR && DenoiserMode.parse("nrd_reblur") == DenoiserMode.NRD_REBLUR, "nrd = reblur");
        check(DenoiserMode.parse(" NRD_RELAX ") == DenoiserMode.NRD_RELAX, "relax, trimmed, case-insensitive");
        boolean threw = false;
        try {
            DenoiserMode.parse("dlss");
        } catch (IllegalArgumentException e) {
            threw = e.getMessage().contains("dlss");
        }
        check(threw, "unknown value is an error, not a silent OFF");
        check(DenoiserMode.NRD_REBLUR.nativeModeMask() == 1 && DenoiserMode.NRD_RELAX.nativeModeMask() == 2,
            "VKB_NRD_MODE_REBLUR_DIFFUSE_SPECULAR=1, VKB_NRD_MODE_RELAX_DIFFUSE_SPECULAR=2");
        check(DenoiserMode.NRD_REBLUR.packShaderCode() == 0 && DenoiserMode.NRD_RELAX.packShaderCode() == 1, "pack shader codes");
        check(!DenoiserMode.OFF.isNrd() && DenoiserMode.NRD_RELAX.isNrd(), "isNrd");
        check(DenoiserConfig.requested() == DenoiserMode.OFF || System.getProperty("vkrtx.denoiser") != null,
            "default config is OFF unless the property is set");
        check(!NrdDeviceRequirements.requirement().features().isEmpty()
            && NrdDeviceRequirements.requirement().features().size() == 3, "NRD device requirement: three storage-image features");
    }

    private static void versionSupport() {
        check(NrdDenoiser.isSupportedNrdVersion(4, 17), "NRD 4.17 is supported");
        check(NrdDenoiser.isSupportedNrdVersion(4, 18), "NRD 4.18 is supported");
        check(!NrdDenoiser.isSupportedNrdVersion(4, 16), "NRD 4.16 is rejected");
        check(!NrdDenoiser.isSupportedNrdVersion(4, 19), "NRD 4.19 is rejected until validated");
        check(!NrdDenoiser.isSupportedNrdVersion(3, 18), "NRD 3.18 is rejected");
    }

    private static void availability() {
        Path nrd = Path.of("NRD.dll");
        Path wrapper = Path.of("vkb_nrd.dll");
        NrdAvailability missing = NrdAvailability.from(false, "MISSING: NRD.dll", null, null);
        check(!missing.usable() && missing.reason().contains("MISSING"), "library not usable keeps Bedray Core reason");
        NrdAvailability noWrapper = NrdAvailability.from(true, "PRESENT_UNVERIFIED", nrd, null);
        check(!noWrapper.usable() && noWrapper.reason().contains("vkb_nrd.dll"), "wrapper missing is reported by name");
        NrdAvailability noNrd = NrdAvailability.from(true, "x", null, wrapper);
        check(!noNrd.usable() && noNrd.reason().contains("NRD.dll"), "NRD.dll missing");
        NrdAvailability ok = NrdAvailability.from(true, "PRESENT_UNVERIFIED", nrd, wrapper);
        check(ok.usable() && ok.nrdDll().equals(nrd) && ok.wrapperDll().equals(wrapper), "usable with both files");
        boolean threw = false;
        try {
            new NrdAvailability(true, null, wrapper, "x");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "usable without a path is rejected");
    }

    private static void octEncoding() {
        Random rnd = new Random(42);
        for (int i = 0; i < 2000; i++) {
            float x = (float) rnd.nextGaussian();
            float y = (float) rnd.nextGaussian();
            float z = (float) rnd.nextGaussian();
            float len = (float) Math.sqrt(x * x + y * y + z * z);
            x /= len;
            y /= len;
            z /= len;
            float rough = rnd.nextFloat();
            float[] e = NrdPackMath.encodeOct(x, y, z, rough);
            check(e[0] >= 0f && e[0] <= 1f && e[1] >= 0f && e[1] <= 1f && e[2] >= 0f && e[2] <= 1f, "encoded values in UNORM range");
            float[] d = NrdPackMath.decodeOct(e[0], e[1], e[2]);
            near(x, d[0], 1e-4f, "oct round trip x");
            near(y, d[1], 1e-4f, "oct round trip y");
            near(z, d[2], 1e-4f, "oct round trip z (sign preserved)");
            near(Math.max(rough, 1.5f / 512f), d[3], 1e-5f, "roughness round trip");
        }
        float[] below = NrdPackMath.encodeOct(0, 0, -1, 0f);
        check(below[2] < 0.5f, "n.z < 0 puts the roughness channel below 0.5 even for roughness 0 (min roughness keeps the sign)");
    }

    private static void packEncodings() {
        float[] oct = NrdPackMath.packNormalRoughness(0f, 0f, 1f, 0.6f, NrdPackMath.NORMAL_R10G10B10A2_UNORM, NrdPackMath.ROUGHNESS_LINEAR);
        check(oct[3] == 0f, "material id 0 in alpha for the 10-10-10-2 encoding");
        near(0.5f, oct[0], 1e-6f, "straight-up normal x");
        float[] best = NrdPackMath.packNormalRoughness(0.5f, 0f, 0f, 0.3f, NrdPackMath.NORMAL_RGBA16_SNORM, NrdPackMath.ROUGHNESS_LINEAR);
        near(1f, best[0], 1e-6f, "best fit stretches to the unit cube");
        near(0.3f, best[3], 1e-6f, "linear roughness stored as is");
        float[] unorm = NrdPackMath.packNormalRoughness(0f, 0f, -1f, 0.25f, NrdPackMath.NORMAL_RGBA8_UNORM, NrdPackMath.ROUGHNESS_SQRT_LINEAR);
        near(0f, unorm[2], 1e-6f, "UNORM encodings map -1..1 to 0..1");
        near(0.5f, unorm[3], 1e-6f, "sqrt roughness encoding");
        float[] sq = NrdPackMath.packNormalRoughness(0f, 1f, 0f, 0.5f, NrdPackMath.NORMAL_RGBA16_SNORM, NrdPackMath.ROUGHNESS_SQ_LINEAR);
        near(0.25f, sq[3], 1e-6f, "squared roughness encoding");
    }

    private static void reblurPacking() {
        // diffuse: roughness 1 -> normalisation = A + |viewZ| * B
        near(4.0f, NrdPackMath.hitDistanceNormalization(-10f, 3f, 0.1f, 20f, 1.0f), 1e-5f, "normalisation at roughness 1");
        near(80.0f, NrdPackMath.hitDistanceNormalization(-10f, 3f, 0.1f, 20f, 0.0f), 1e-4f, "normalisation at roughness 0 is scaled by C");
        near(1f, NrdPackMath.specMagicCurve(1f, 0.5f), 1e-6f, "spec magic curve at 1");
        near(0f, NrdPackMath.specMagicCurve(0f, 0.5f), 1e-6f, "spec magic curve at 0");
        NrdReblurParams p = NrdReblurParams.defaults();
        float[] v = NrdPackMath.packReblurDiffuse(0.2f, 0.4f, 0.2f, 2.0f, -10f, p);
        near(0.3f, v[0], 1e-6f, "Y = 0.25 r + 0.5 g + 0.25 b");
        near(0f, v[1], 1e-6f, "Co");
        near(0.1f, v[2], 1e-6f, "Cg");
        near(0.5f, v[3], 1e-6f, "norm hit dist = 2 / 4");
        float[] sat = NrdPackMath.packReblurDiffuse(1f, 1f, 1f, 100f, -10f, p);
        near(1f, sat[3], 0f, "norm hit dist saturates at 1");
        float[] bad = NrdPackMath.packReblurDiffuse(Float.NaN, 1f, 1f, Float.POSITIVE_INFINITY, -10f, p);
        check(bad[0] == 0f && bad[1] == 0f && bad[2] == 0f && bad[3] == 0f, "NaN/Inf input is zeroed, not forwarded to NRD");
        float[] big = NrdPackMath.packReblurDiffuse(1e9f, 0f, 0f, 1f, -10f, p);
        near(NrdPackMath.FP16_MAX * 0.25f, big[0], 1f, "radiance clamped to FP16 max before YCoCg");
        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            float r = rnd.nextFloat() * 4f;
            float g = rnd.nextFloat() * 4f;
            float b = rnd.nextFloat() * 4f;
            float[] y = NrdPackMath.linearToYCoCg(r, g, b);
            float[] back = NrdPackMath.yCoCgToLinear(y[0], y[1], y[2]);
            near(r, back[0], 1e-4f, "YCoCg round trip r");
            near(g, back[1], 1e-4f, "YCoCg round trip g");
            near(b, back[2], 1e-4f, "YCoCg round trip b");
        }
    }

    private static void relaxPacking() {
        float[] v = NrdPackMath.packRelaxDiffuse(0.2f, 0.4f, 0.6f, 3f);
        check(v[0] == 0.2f && v[1] == 0.4f && v[2] == 0.6f && v[3] == 3f, "RELAX keeps radiance and world-unit hit distance");
        float[] bad = NrdPackMath.packRelaxDiffuse(0f, Float.NaN, 0f, 1f);
        check(bad[0] == 0f && bad[1] == 0f && bad[2] == 0f && bad[3] == 1f, "RELAX zeroes invalid radiance, keeps a valid hit distance");
    }

    private static void cameraMath() {
        // Engine projection (y down, as the RT shader assumes: row 0 of the image is ndc.y = -1) built from a standard one.
        Matrix4f standard = new Matrix4f().perspective((float) Math.toRadians(70.0), 16f / 9f, 0.05f, 1000f);
        Matrix4f engine = NrdCameraMath.negateClipY(standard, new Matrix4f());
        Matrix4f nrd = NrdCameraMath.negateClipY(engine, new Matrix4f());   // what NrdDenoiser hands to NRD
        check(nrd.equals(standard, 1e-6f), "negating twice restores the projection");
        float[] above = NrdCameraMath.projectToPixel(nrd, 0f, 1f, -5f, 1280, 720);
        float[] center = NrdCameraMath.projectToPixel(nrd, 0f, 0f, -5f, 1280, 720);
        float[] right = NrdCameraMath.projectToPixel(nrd, 1f, 0f, -5f, 1280, 720);
        near(640f, center[0], 1e-3f, "axis point at the horizontal centre");
        near(360f, center[1], 1e-3f, "axis point at the vertical centre");
        check(above[1] < 360f, "a point above the axis lands on the upper half (row 0 is the top) with the NRD projection");
        check(right[0] > 640f, "a point to the right lands on the right half");
        float[] wrong = NrdCameraMath.projectToPixel(engine, 0f, 1f, -5f, 1280, 720);
        check(wrong[1] > 360f, "the engine projection used directly would flip the vertical axis (why NRD gets the negated one)");

        Matrix4f rot = new Matrix4f().rotateY((float) Math.toRadians(30));
        Matrix4f prev = NrdCameraMath.previousWorldToView(rot, 10.0, 64.0, 10.0, 11.0, 64.0, 12.0, new Matrix4f());
        // a point P (relative to the current camera) is P + (now - prev) = P + (1, 0, 2) relative to the previous camera
        org.joml.Vector3f expect = rot.transformPosition(new org.joml.Vector3f(3f + 1f, 0f, -5f + 2f));
        org.joml.Vector3f got = prev.transformPosition(new org.joml.Vector3f(3f, 0f, -5f));
        near(expect.x, got.x, 1e-5f, "previous world-to-view includes the camera delta x");
        near(expect.z, got.z, 1e-5f, "previous world-to-view includes the camera delta z");
        // precision: far from the origin the double subtraction keeps the small delta exact
        Matrix4f far = NrdCameraMath.previousWorldToView(new Matrix4f(), 1.0e7, 0, 1.0e7, 1.0e7 + 0.5, 0, 1.0e7, new Matrix4f());
        near(0.5f, far.m30(), 0f, "delta computed in double");
        check(NrdCameraMath.isTeleport(0, 0, 0, 100, 0, 0, 32), "100 blocks is a teleport");
        check(!NrdCameraMath.isTeleport(0, 0, 0, 1, 0, 1, 32), "walking is not");
    }

    private static void shaderDefines() {
        String src = "#version 460\nvoid main() {}\n";
        String out = RtShaderCompiler.withDefines(src, "#define A 1\n");
        check(out.equals("#version 460\n#define A 1\nvoid main() {}\n"), "defines go after the #version line: " + out);
        check(RtShaderCompiler.withDefines(src, "").equals(src), "no defines: unchanged");
        boolean threw = false;
        try {
            RtShaderCompiler.withDefines("void main() {}\n", "#define A 1\n");
        } catch (RuntimeException e) {
            threw = true;
        }
        check(threw, "source without #version is rejected");
    }

    private static void shadersCompile() {
        ByteBuffer plain = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp");
        ByteBuffer gb = RtShaderCompiler.compileResource(RtPipeline.SHADER_PATH, "rt_rays.comp+gbuffer", RtPipeline.GBUFFER_DEFINE);
        check(gb.getInt(0) == 0x07230203 && gb.remaining() > plain.remaining(), "gbuffer variant compiles and is larger than the plain shader");
        ByteBuffer pack = RtShaderCompiler.compileResource(NrdPackPipeline.SHADER_PATH, "rt_pack_nrd.comp:pack", "#define VKB_PASS_PACK 1" + (char) 10);
        ByteBuffer compose = RtShaderCompiler.compileResource(NrdPackPipeline.SHADER_PATH, "rt_pack_nrd.comp:compose",
            "#define VKB_PASS_COMPOSE 1" + (char) 10);
        check(pack.getInt(0) == 0x07230203 && pack.remaining() > 500, "pack pass SPIR-V");
        check(compose.getInt(0) == 0x07230203 && compose.remaining() > 500, "compose pass SPIR-V");
        boolean threw = false;
        try {
            RtShaderCompiler.compileResource(NrdPackPipeline.SHADER_PATH, "rt_pack_nrd.comp:none");
        } catch (RuntimeException e) {
            threw = true;
        }
        check(threw, "the pack shader without a pass define is an error (#error), not a silent empty shader");
    }

    /** Loads a locally built vkb_nrd.dll (env VKB_NRD_NATIVES or local reference); prints SKIPPED when it is not there. */
    private static void nativeBinding() {
        String dir = System.getenv("VKB_NRD_NATIVES");
        Path dll = Path.of(dir != null ? dir : ".").resolve("vkb_nrd.dll");
        if (!Files.isRegularFile(dll)) {
            System.out.println("    [SKIPPED] nrd native binding: " + dll + " not found");
            return;
        }
        try (NrdNative n = NrdNative.load(dll)) {
            check(n.apiVersion() == NrdLayouts.API_VERSION, "vkb_nrd.dll API version equals the Java binding");
            check(n.requiredFeatures() == 7, "required device features mask (read, write without format, extended formats) = 7");
            check(n.lastError() != null, "last error string is never null");
            // an uninitialised library must refuse to create a denoiser with an explanation (no crash, no silent success)
            NrdException e = null;
            try {
                n.createDenoiser(64, 64, 1, 1);
            } catch (NrdException ex) {
                e = ex;
            }
            check(e != null && e.code() != 0 && e.getMessage().contains("vkb_nrd_init"), "create before init fails with a reason: " + (e == null ? "no exception" : e.getMessage()));
            System.out.println("    [native] vkb_nrd.dll loaded through FFM: api=0x" + Integer.toHexString(n.apiVersion()) + " features=0x"
                + Integer.toHexString(n.requiredFeatures()));
        }
    }
}
