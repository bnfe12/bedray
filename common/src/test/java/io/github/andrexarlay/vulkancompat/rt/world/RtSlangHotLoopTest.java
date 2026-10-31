package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cost invariants of the typed path-tracing hot loop. The deleted {@code RtPathHotLoopTest} pinned the same four rules
 * in {@code rt_path.comp}: {@code spp} full paths run per pixel, so a duplicated decode, a sky evaluated on a hit, a
 * key-light draw outside its branch or a shading normal a shadow ray never reads is paid at screen resolution.
 *
 * <p>Everything here is behaviour preserving. The typed loop already obeys the cost rules; this file keeps a later edit
 * from quietly restoring the work the port removed. The one rule the port had lost, the transmittance walk that built a
 * shading normal, is restored in {@code surface/surface.slang} with the {@code wantNormal} switch the legacy decoder had.
 */
class RtSlangHotLoopTest {

    private static final String SLANG = "src/main/resources/shaders/slang/";
    private static final String TRACER = SLANG + "pathtrace/path_tracer.slang";
    private static final String SURFACE = SLANG + "surface/surface.slang";
    private static final String VISIBILITY = SLANG + "surface/visibility.slang";
    private static final String RENDERER =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/world/RtFrameRenderer.java";

    @Test
    void theWalkDecodesEachAcceptedHitExactlyOnce() throws IOException {
        String body = block(read(TRACER), read(TRACER).indexOf("PathTraceResult tracePathWithField<"));
        assertEquals(1, count(body, "decodeSurface("),
            "one decode per accepted candidate: a second decode would repeat the material, normal and water work the "
                + "same hit already paid for");
        assertEquals(1, count(body, "SurfaceSample surface ="),
            "one SurfaceSample local feeds NEE, the BSDF and the guide record; three live copies is the spill the "
                + "legacy test removed");
        assertEquals(1, count(body, "throughput * surface.material.emission;"),
            "the emissive exitance is added once at the vertex, not re-evaluated per consumer");
        assertEquals(1, count(body, "result.primary.valid = true;"),
            "the pixel's guide record is filled from the single primary decode");
    }

    @Test
    void thePrimaryRecordIsFilledWhereTheFirstSurfaceIsAccepted() throws IOException {
        String tracer = read(TRACER);
        String body = block(tracer, tracer.indexOf("PathTraceResult tracePathWithField<"));
        String first = block(body, body.indexOf("if (!firstSurfaceAccepted) {"));
        assertTrue(first.contains("result.firstHitDistance = primaryAdvance + hit.t;"),
            "the primary distance is captured at the hit, not reconstructed after the walk");
        assertTrue(first.contains("result.primaryMotion = float4(traversal.surfaceMotion(hit),"),
            "entity motion is read from the same accepted surface");
        assertTrue(first.contains("result.flags |= PATH_TRACE_FLAG_PRIMARY_HIT;"));
        int firstBranch = body.indexOf("if (!firstSurfaceAccepted) {");
        int accepted = body.indexOf("result.surfaceHits++;");
        assertTrue(accepted > firstBranch, "the hit counter advances after the first-hit branch");

        String guides = block(body, body.indexOf("if (receiver.primary) {"));
        for (String field : new String[] {"result.primary.valid = true;", "result.primary.position = hit.position;",
            "result.primary.normal = normal;", "result.primary.albedo = clamp(surface.material.albedo",
            "result.primary.specularF0 = surface.material.f0;", "result.primary.roughness = surface.material.roughness;",
            "result.primary.metallic = surface.material.metallic;", "result.primary.direct = direct;"}) {
            assertTrue(guides.contains(field), "the guide block lost " + field);
        }
    }

    @Test
    void theSkyIsPaidOnlyOnAMiss() throws IOException {
        String tracer = read(TRACER);
        String body = block(tracer, tracer.indexOf("PathTraceResult tracePathWithField<"));
        assertEquals(1, count(body, "environment.radiance(escape)"),
            "the environment is evaluated on the ray that leaves the world, not before the trace");
        assertTrue(body.indexOf("float3 escape = ray.direction;") > body.indexOf("if (!hit.hit) {"),
            "and the escape direction is the miss's own ray, bent only on the way out of a water body");
        int miss = body.indexOf("if (!hit.hit) {");
        int sky = body.indexOf("result.radiance += throughput * escapeWeight * environment.radiance(escape);");
        assertTrue(miss > 0 && sky > miss, "a hit overwrites the colour, so the sky belongs to the miss branch");
        assertTrue(block(body, miss).contains("result.flags |= PATH_TRACE_FLAG_ENVIRONMENT;"),
            "the miss still records why the path ended");
    }

    @Test
    void anAmbientOnlyBounceStopsAtTheFirstOpaqueSurface() throws IOException {
        String body = block(read(TRACER), read(TRACER).indexOf("PathTraceResult tracePathWithField<"));
        String pending = block(body, body.indexOf("if (ambientOnlyPending) {"));
        assertTrue(pending.contains("break;"), "an AO bounce that reaches geometry collects nothing");
        assertFalse(pending.contains("result.radiance +="),
            "the occluded sky must not leak into the ambient term past a wall");
        assertTrue(body.contains(
            "continuationAmbientOnly = (options.flags & PATH_TRACE_OPTION_AMBIENT_ONLY_BOUNCE) != 0u;"),
            "the AO profile still selects the ambient-only continuation");
        assertTrue(body.contains("ambientOnlyPending = continuationAmbientOnly;"),
            "the flag has to travel to the next iteration with the ray");
        assertTrue(body.contains("if ((options.flags & PATH_TRACE_OPTION_NO_DIFFUSE_BOUNCE) != 0u) {"));
        assertTrue(body.contains("if ((options.flags & PATH_TRACE_OPTION_NO_SPECULAR_BOUNCE) != 0u) {"),
            "a disabled lobe stops the path instead of tracing a branch whose light is discarded");
    }

    @Test
    void theKeyLightIsDrawnOncePerVertex() throws IOException {
        String body = block(read(TRACER), read(TRACER).indexOf("PathTraceResult tracePathWithField<"));
        assertEquals(2, count(body, "lights.sample(receiver, seed)"),
            "one draw on the reservoir branch and one on the plain branch: a third would shade the same vertex twice");
        assertEquals(1, count(body, "reuse.reuseSample(receiver, seed)"),
            "the reservoir is read once, at the branch that decided to use it");
        int reuseBranch = body.indexOf("if (receiver.primary && reuse.reuseActive() && !emitterBaked) {");
        int reuseDraw = body.indexOf("reuse.reuseSample(receiver, seed)");
        int plainDraw = body.indexOf("LightSample lightSample = lights.sample(receiver, seed);");
        assertTrue(reuseBranch > 0 && reuseDraw > reuseBranch, "the reused sample is read inside its branch");
        assertTrue(plainDraw > reuseBranch, "the plain draw is the other branch, not a second sample of the reused one");
    }

    @Test
    void theReservoirResetsOnTheLightListFingerprint() throws IOException {
        String renderer = read(RENDERER);
        assertTrue(renderer.contains("params.emitterFingerprint = emitters.fingerprint();"),
            "the reservoir must be reset by the light list, not by any section build");
        assertFalse(renderer.contains("world.structureVersion()"),
            "streaming sections and camera re-anchoring must not invalidate the ReSTIR history");
    }

    @Test
    void theTransmittanceWalkDoesNotBuildAShadingNormal() throws IOException {
        String surface = read(SURFACE);
        String visibility = read(VISIBILITY);
        assertEquals(2, count(visibility,
                "decodeSurface(atlas, blockMaterials, hit, options, current.direction, false)"),
            "both filtered walks skip the normal work: the filter reads the material, the geometric normal and the tint");
        assertTrue(read(TRACER).contains("decodeSurface(atlas, blockMaterials, face, materialOptions, up, false)"),
            "the camera's water probe only needs the FLUID tag and the face normal");

        String decode = block(surface, surface.indexOf("SurfaceHit hit, MaterialSamplingOptions options, "
            + "float3 rayDirection, bool wantNormal) {"));
        int entityGate = decode.indexOf("if (wantNormal && !endPortal && !outline");
        int entityNormal = decode.indexOf("decodeEntityAutoNormal(");
        int atlasGate = decode.indexOf("if (wantNormal) {");
        int atlasNormal = decode.indexOf("decodeAtlasNormal(");
        int waterGate = decode.indexOf("if (wantNormal) {", atlasGate + 1);
        int waveNormal = decode.indexOf("waterStyledWaveNormal(");
        assertTrue(entityGate > 0 && entityNormal > entityGate, "the entity auto normal is gated");
        assertTrue(atlasGate > entityGate && atlasNormal > atlasGate, "the atlas normal and its AO texel are gated");
        assertTrue(waterGate > atlasGate && waveNormal > waterGate, "the wave normal is gated");
        assertTrue(decode.indexOf("material.roughness = options.waterWaveMix") > waterGate,
            "the water sheen follows the wave sample, so it belongs inside the gate too");
        assertTrue(decode.indexOf("float footprint") > waterGate,
            "the footprint is only needed by the wave sample");
        assertTrue(surface.contains("return decodeSurface(atlas, blockMaterials, hit, options, rayDirection, true);"),
            "a shaded caller keeps the full decode");

        for (String shaded : new String[] {"float3 surfaceTransmittance(", "float3 surfaceInterfaceTransmittance(",
            "float3 foliageLayerTransmittance(", "bool surfaceIsHole("}) {
            String body = block(surface, surface.indexOf(shaded));
            assertFalse(body.contains("shadingNormal"), shaded + " reads the normal the walk skipped");
            assertFalse(body.contains("ambientOcclusion"), shaded + " reads the AO texel the walk skipped");
        }
        assertTrue(block(surface, surface.indexOf("float3 surfaceTransmittance("))
            .contains("surface.hit.geometricNormal"), "the filter is a function of the face normal");
        assertTrue(block(surface, surface.indexOf("float3 surfaceInterfaceTransmittance("))
            .contains("surface.hit.geometricNormal"), "the interface share is a function of the face normal");
    }

    // ---- helpers ----

    private static String read(String relative) throws IOException {
        return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String block(String source, int at) {
        assertTrue(at >= 0, "anchor not found");
        int depth = 0;
        for (int i = source.indexOf('{', at); i >= 0 && i < source.length(); i++) {
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

    private static int count(String haystack, String needle) {
        int hits = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            hits++;
        }
        return hits;
    }
}