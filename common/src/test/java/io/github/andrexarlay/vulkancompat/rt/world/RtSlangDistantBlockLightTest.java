package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emitter list is sorted by importance from the camera and holds only emitters within the range the frame
 * collected it over, while a candidate farther than {@code EMITTER_RADIUS} from the shaded point is rejected. A
 * receiver far from the player therefore has no usable candidate at all and its lamps are unlit (torches in a distant
 * village, a lit cave across the valley). The deleted {@code rt_common_shade.glsl} owned the handover: past the list's
 * reach the vanilla block-light field takes the term over, and only there - baked light next to the player is the leak
 * the traced emitters replaced.
 *
 * <p>This class restores the guards of the deleted {@code RtDistantBlockLightTest} against the Slang path, plus the
 * pieces the typed path had to grow: the list range travels as a pass option (the tracer never sees the sampler), the
 * handover is skipped for a receiver the list cannot serve (the record is cleared, not merely left unlit), and the
 * feature follows the same switch as the list itself.
 */
class RtSlangDistantBlockLightTest {

    private static final String EMITTERS = "src/main/resources/shaders/slang/light/block_emitters.slang";
    private static final String TRACER = "src/main/resources/shaders/slang/pathtrace/path_tracer.slang";
    private static final String ENTRY = "src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang";
    private static final String LIGHT_CONTRACT = "src/main/resources/shaders/slang/contracts/light.slang";
    private static final String PASS_HOST =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtPathTraceSlangPass.java";
    private static final String SHADER_HOST =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtSlangPathPass.java";
    private static final String RENDERER =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java";
    private static final String NATIVE_CORE = "../native/bedray_core/src/path_trace_pass.cpp";

    // Mirrors of the shader constants.  Duplicate literals on purpose: if either side drifts, the mirror stops
    // describing what ships and the numeric tests below stop meaning anything.
    private static final float EMITTER_RADIUS = 24.0f;
    private static final float FIELD_GAIN = 0.05f;
    private static final float FAR_FULL = 0.999f;
    private static final int OPTION_BLOCK_EMITTERS = 1 << 16;

    // ---- the handover's arithmetic, mirrored from block_emitters.slang ----

    private static float farMix(float cameraDist, float listRange) {
        float inner = Math.max(listRange - EMITTER_RADIUS, 8.0f);
        return smoothstep(inner, listRange + EMITTER_RADIUS, cameraDist);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.min(Math.max((x - edge0) / (edge1 - edge0), 0.0f), 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float[] vanilla(float blockLight) {
        float level = Math.min(Math.max(blockLight, 0.0f), 1.0f) * FIELD_GAIN;
        return new float[] {1.0f * level, 0.82f * level, 0.60f * level};
    }

    @Test
    void theFallbackStartsWhereTheEmitterListStopsBeingComplete() {
        String emitters = read(EMITTERS);
        assertTrue(emitters.contains("float blockLightFarMix(float cameraDist, float listRange)"),
            "the distance rule has to be shared by both profiles, not copied into each pass");
        assertTrue(emitters.contains(
                "return smoothstep(max(listRange - EMITTER_RADIUS, 8.0), listRange + EMITTER_RADIUS, cameraDist);"),
            "the list stops being complete at listRange - EMITTER_RADIUS, but a negative inner edge (a collection"
                + " range as low as 8) would leak baked light onto the player");
        assertTrue(emitters.contains("static const float BLOCK_LIGHT_FAR_FULL = 0.999;"),
            "the far end of the band is the legacy 0.999: past it the traced batch has nothing left to sample");

        // the band of the path profile: min(path distance, 128) blocks, so the handover runs 104..152 blocks away
        assertEquals(0.0f, farMix(103.9f, 128.0f), 1.0e-6f, "inside the complete part of the list nothing is baked");
        assertEquals(0.5f, farMix(128.0f, 128.0f), 1.0e-6f, "the middle of the band is an even mix");
        assertEquals(1.0f, farMix(152.1f, 128.0f), 1.0e-6f, "past the band the field is the whole term");
        assertTrue(farMix(140.0f, 128.0f) < FAR_FULL * 0.9f && farMix(151.0f, 128.0f) < FAR_FULL,
            "the skip only fires at the very end of the band: the estimate is still mixed in through most of it");
        assertTrue(farMix(151.5f, 128.0f) > FAR_FULL,
            "past ~99% of the band the traced batch has nothing left to sample and the branch is skipped");
        // the floor: a list range of 8 (the ray profile's smallest emissive distance + 8) must not bake next to the eye
        assertEquals(0.0f, farMix(7.9f, 16.0f), 1.0e-6f,
            "max(listRange - EMITTER_RADIUS, 8.0) keeps a short list from painting the room around the player");
        assertEquals(0.5f, farMix(24.0f, 16.0f), 1.0e-6f, "the band of a short list still hands over smoothly");
    }

    @Test
    void theFieldIsScaledToTheTracedEmitters() {
        String emitters = read(EMITTERS);
        assertTrue(emitters.contains("static const float BLOCK_LIGHT_FIELD_GAIN = 0.05;"),
            "the lightmap texel is ~0.87 at level 13 while the traced torch reads ~0.05: the field has to be divided by"
                + " ~20 or the far field is 5..15x brighter than the same torch next to the player");
        assertTrue(emitters.contains("static const float3 BLOCK_LIGHT_FIELD_TINT = float3(1.0, 0.82, 0.60);"),
            "a typed path pass binds no lightmap, so the field keeps the warm tint the lightmap sampling replaced");
        assertTrue(emitters.contains(
                "return BLOCK_LIGHT_FIELD_TINT * clamp(light.x, 0.0, 1.0) * BLOCK_LIGHT_FIELD_GAIN;"),
            "the vanilla block-light level is clamped and gained inside the one field term");
        assertEquals(1, count(emitters, "BLOCK_LIGHT_FIELD_TINT *"),
            "one vanilla field term, in vanillaBlockIrradiance: a second copy would be a second look");
        assertEquals(1, count(emitters, "BLOCK_LIGHT_FIELD_GAIN;"), "the gain is applied exactly once");

        float[] half = vanilla(0.5f);
        assertEquals(0.025f, half[0], 1.0e-7f, "level 0.5 * 0.05");
        assertEquals(0.0205f, half[1], 1.0e-7f, "the tint keeps the field warm");
        assertEquals(0.015f, half[2], 1.0e-7f);
        assertEquals(0.0f, vanilla(0.0f)[0], 0.0f, "a face with no vanilla block light has no field");
        assertEquals(vanilla(1.0f)[0], vanilla(4.0f)[0], 0.0f, "the level is clamped, not extrapolated");
    }

    @Test
    void theHandoverIsAMixNotAMax() {
        String tracer = read(TRACER);
        assertEquals(2, count(tracer, "* (1.0 - emitterFar)"),
            "both the reused and the fresh emitter estimate fade by the band's near share; mix, not max: the traced"
                + " estimator reads zero on most pixels of the band and max() would leave a bright ring around the"
                + " player");
        assertTrue(tracer.contains("direct += pathBakedBlockLight(surface.material, view, normal, hit.light) * emitterFar;"),
            "the baked field carries the band's far share, applied once after both branches");
        for (String line : tracer.split("\n")) {
            assertTrue(!(line.contains("emitterFar") && line.contains("max(")),
                "no max() over the handover, only the mix: " + line.trim());
        }
        assertEquals(1, count(tracer, "pathBakedBlockLight(surface.material, view, normal, hit.light)"),
            "one baked term per vertex");
    }

    @Test
    void theDistanceIsMeasuredFromTheCameraNotFromTheLastInterface() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("emitterFar = blockLightFarMix(length(hit.position), listRange);"),
            "hit.position is camera-relative: a surface behind a glass pane must not read as next to the player");
        assertEquals(0, count(tracer, "blockLightFarMix(hit.t"), "the primary hit distance is not the camera distance");
        assertEquals(0, count(tracer, "blockLightFarMix(surface.hit.t"), "the interface distance is not either");
        assertTrue(tracer.contains("float listRange = options.emitterListRange > 0.0 ? options.emitterListRange : options.maxDistance;"),
            "the range travels as a pass option, because the tracer receives a generic ILightSampler");
    }

    @Test
    void onlyThePrimaryDiffuseReceiverWithBakedLightFallsBack() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("if (receiver.primary && (options.flags & PATH_TRACE_OPTION_BLOCK_EMITTERS) != 0u"),
            "only the primary vertex has a camera distance; a bounce is shaded where the previous vertex sent it and"
                + " keeps the traced estimate");
        assertTrue(tracer.contains("&& !materialIsMetal(surface.material) && hit.light.x > 0.0)"),
            "the legacy branch was diffuse-only, and a face with no vanilla block light (a LabPBR glow, an emissive"
                + " item) has nothing to fall back to");
        String helper = block(tracer, tracer.indexOf("float3 pathBakedBlockLight("));
        assertTrue(helper.contains("float3 fresnelView = fresnelSchlick(max(dot(normal, view), 0.0), bsdfF0(material));"),
            "the energy split is the view-angle Fresnel of the same interface the BSDF would have used");
        assertTrue(helper.contains("float specularShare = max(fresnelView.r, max(fresnelView.g, fresnelView.b));"),
            "the legacy Fsc is the largest channel, not the luminance");
        assertTrue(helper.contains("bsdfReflectionAlbedo(material) * (1.0 - specularShare) * material.ambientOcclusion"),
            "the albedo (once), the energy the interface did not take and the LabPBR AO");
        assertEquals(1, count(helper, "bsdfReflectionAlbedo(material)"), "the albedo is applied once");
        String emitters = read(EMITTERS);
        int field = emitters.indexOf("float3 vanillaBlockIrradiance(");
        assertTrue(field > 0, "the one vanilla field term");
        assertEquals(0, count(block(emitters, field), "albedo"),
            "the field itself stays albedo-free: the caller multiplies it");
    }

    @Test
    void theExhaustedListSkipsTheEmitterBranchButKeepsTheKeyLight() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("emitterFar = blockLightFarMix(length(hit.position), listRange);"));
        assertTrue(tracer.contains("emitterBaked = emitterFar >= BLOCK_LIGHT_FAR_FULL;"),
            "past the band the traced batch would spend its candidates on draws that are all rejected");
        assertTrue(tracer.contains("receiver.bakedBlockLight = emitterBaked;"),
            "the sampler reads the verdict off the receiver, so a composite cannot draw the branch the field replaces");
        assertTrue(tracer.contains("if (receiver.primary && reuse.reuseActive() && !emitterBaked)"),
            "the reservoir branch is skipped too: an emitter record would be shaded on top of the field");
        assertEquals(2, count(tracer, "lights.sample(receiver, seed)"),
            "the key light is sampled in both branches: the handover replaces the emitter list, never the sun");
        int baked = tracer.indexOf("receiver.bakedBlockLight = emitterBaked;");
        int sample = tracer.indexOf("lights.sample(receiver, seed)");
        assertTrue(baked > 0 && baked < sample, "the verdict has to be set before the sampler is asked");
        assertTrue(tracer.contains("if (!reuseHandled && reuse.reuseActive()) {\n"
                + "        reuse.reuseClear();"),
            "a primary vertex that skipped the reservoir has no record of its own: the end-of-path clear keeps a stale"
                + " torch from glowing through history after the player walked away");
    }

    @Test
    void theEmitterSamplerHonoursTheHandover() {
        String emitters = read(EMITTERS);
        String composite = block(emitters, emitters.indexOf("struct SunAndBlockEmitterSampler : ILightSampler"));
        assertTrue(composite.contains("&& !receiver.bakedBlockLight;"),
            "the composite must not draw the emitter branch for a receiver the baked field carries");
        assertTrue(composite.contains("return keyLight.sample(receiver, seed);"),
            "with the emitter branch gone the key light keeps the whole density instead of being renormalised");
        String contract = read(LIGHT_CONTRACT);
        assertTrue(contract.contains("bool   bakedBlockLight;"),
            "the verdict travels on the receiver: a sampler cannot infer it from position or normal");
        assertTrue(contract.contains("receiver.bakedBlockLight = false;"),
            "every ordinary caller keeps the traced estimate");
    }

    @Test
    void theHostSendsTheSameReachTheListWasCollectedOver() {
        String entry = read(ENTRY);
        assertTrue(entry.contains("options.emitterListRange = emitterSampler.maxDistance;"),
            "the tracer reads the range the sampler windows its candidates with, not a second copy of the rule");
        String host = read(SHADER_HOST);
        assertTrue(host.contains("return rayMode ? RtSettings.emissiveDistance() + 8f : Math.min(params.rayDistance, 128f);"),
            "the path profile's list radius is min(path distance, 128) blocks; the ray profile collects to"
                + " emissiveDistance() + 8");
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("Math.min(io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.pathMaxDistance(), 128)"),
            "the list is rebuilt over min(path distance, 128) blocks in path mode (RtEmitters.update)");
        assertTrue(renderer.contains("RtSettings.emissiveDistance() + 8f"),
            "and over emissiveDistance() + 8 blocks in ray mode");
        String nativeCore = read(NATIVE_CORE);
        assertTrue(nativeCore.contains("trace_push.flags = desc.path.flags & ~kPathOptionRestirPtMask;")
                && nativeCore.contains("constexpr std::uint32_t kPathOptionRestirPtMask = 0x1Fu << 17u;"),
            "the native core forwards the host's option word bit for bit except the ReSTIR PT ring state (17..21), so"
                + " bit 16 needs no native change");
    }

    @Test
    void theSwitchThatDisablesEmittersDisablesTheField() {
        String host = read(SHADER_HOST);
        assertTrue(host.contains("private boolean blockEmittersEnabled() {\n"
                + "        return RtConfig.SLANG_BLOCK_EMITTERS && (!rayMode || RtSettings.emissive());"),
            "one verdict: the bound list, the reservoir branch and the baked field follow the same switch");
        assertTrue(host.contains("int emitterCount = blockEmitters ? Math.max(0, params.emitterCount) : 0;"),
            "the list is dropped with the field, so 'RT Block Light off' stays 'no emissive lighting'");
        assertTrue(host.contains("| (blockEmitters ? RtPathTraceSlangPass.OPTION_BLOCK_EMITTERS : 0)\n"),
            "the push word carries the same verdict to the shader");
        assertTrue(read(PASS_HOST).contains("static final int OPTION_BLOCK_EMITTERS = 1 << 16;"),
            "mirror of the shader bit 16");
        String tracer = read(TRACER);
        assertTrue(tracer.contains("static const uint PATH_TRACE_OPTION_BLOCK_EMITTERS      = 1u << 16u;"),
            "bit 16 of the push word: bits 8..15 are the pixel-shadow cell size, 0..7 the effect set");
        assertTrue((OPTION_BLOCK_EMITTERS & 0xFFFF) == 0, "the bit must not collide with the size or the effect set");
        assertEquals(1, OPTION_BLOCK_EMITTERS >>> 16, "the bit is 16");
        assertTrue(tracer.contains("if (receiver.primary && (options.flags & PATH_TRACE_OPTION_BLOCK_EMITTERS) != 0u"),
            "a frame that traced no emitters has no list to hand over from and keeps tracing exactly as before");
    }

    // ---- source helpers ----

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }

    private static String block(String source, int at) {
        int depth = 0;
        for (int i = source.indexOf('{', at); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced block at offset " + at);
    }

    private static int count(String source, String needle) {
        int at = 0;
        int hits = 0;
        while (true) {
            int found = source.indexOf(needle, at);
            if (found < 0) {
                return hits;
            }
            hits++;
            at = found + needle.length();
        }
    }
}
