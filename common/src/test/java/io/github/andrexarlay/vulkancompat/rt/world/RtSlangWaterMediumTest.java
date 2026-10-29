package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The typed path tracer carries the water body as a medium: a refracted transmission through a horizontal fluid face
 * enters or leaves it, every segment inside is attenuated by the body's extinction, and each segment adds the light it
 * scatters towards the eye. The eye-visible colour of water is therefore distance, not a tint painted on the interface.
 * <p>
 * {@link RtWaterMediumTest} pins the legacy GLSL reference numerics; this guard keeps the Slang port on the same
 * numbers and keeps the three places that can silently break the model shut: a tinted interface (double filter), a
 * shadow ray that ignores the body (underwater surfaces lit through clear air), and a boundary toggle that fires on a
 * reflection instead of a refraction (the path would think it left the water on a mirror bounce).
 */
class RtSlangWaterMediumTest {

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String WATER = SHADERS + "/surface/water.slang";
    private static final String SURFACE = SHADERS + "/surface/surface.slang";
    private static final String VISIBILITY = SHADERS + "/surface/visibility.slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";

    // Same constants as RtWaterMediumTest: the Slang port must not drift from the legacy model it replaces.
    private static final double CLEAR_R = 0.17;
    private static final double CLEAR_G = 0.036;
    private static final double CLEAR_B = 0.013;
    private static final double SCATTERING = 0.055;
    private static final double FLOOR = 0.65;
    private static final double DEPTH_FALLOFF = 0.65;
    /** Ocean biome tint (0x3F76E4) after the baked face shade is divided out. */
    private static final double[] OCEAN_TINT = {0.25, 0.46, 0.89};

    @Test
    void slangMediumIsTheLegacyMedium() {
        String water = read(WATER);
        assertTrue(water.contains("static const float3 WATER_CLEAR_ABSORPTION = float3(0.17, 0.036, 0.013);"),
            "WATER_CLEAR_ABSORPTION drifted from the legacy model");
        assertTrue(water.contains("static const float WATER_SCATTERING = 0.055;"),
            "WATER_SCATTERING drifted from the legacy model");
        assertTrue(water.contains("static const float WATER_MULTIPLE_SCATTER_FLOOR = 0.65;"),
            "WATER_MULTIPLE_SCATTER_FLOOR drifted from the legacy model");
        String sigma = block(water, "float3 waterSigmaT(float3 tintIn) {");
        assertTrue(sigma.contains("WATER_CLEAR_ABSORPTION + dye + float3(WATER_SCATTERING)"),
            "sigmaT = clear absorption + biome dye + scattering");
        String scatter = block(water, "float3 waterInScatter(");
        assertTrue(scatter.contains("WATER_MULTIPLE_SCATTER_FLOOR"), "the floor is what keeps deep water from fading to black");
        assertTrue(scatter.contains("exp(-medium.sigmaT * depth * 0.65)"), "the legacy depth falloff");
        assertTrue(scatter.contains("medium.scatterAlbedo"), "in-scatter is sigmaS/sigmaT weighted");
        assertTrue(scatter.contains("(float3(1.0) - transmittance)"), "a segment scatters only the light it did not transmit");
    }

    @Test
    void oneBlockOfWaterKeepsTheBottomVisibleAndRedGoesFirst() {
        double[] tint = transmittance(OCEAN_TINT, 1.0);
        assertTrue(tint[1] > 0.75, "1 block must pass most green so the bottom stays visible, got " + tint[1]);
        assertTrue(tint[0] < tint[2], "clear water absorbs red before blue");
        double[] deep = segment(OCEAN_TINT, 20.0, 48.0, 0.45);
        assertTrue(luminance(deep) > 0.02, "a 20-block column must not be black, got " + luminance(deep));
        assertTrue(deep[2] > 2.0 * deep[0], "deep water must stay blue");
    }

    @Test
    void theInterfaceIsClearAndTheColourIsDistance() {
        String surface = read(SURFACE);
        String fluid = block(surface, "&& waterIsSurface(true, material.f0");
        assertTrue(fluid.contains("material.ior = WATER_IOR;"), "the interface refracts at water's index");
        assertTrue(fluid.contains("material.transmission = 1.0;"), "a fluid is a full optical interface");
        assertTrue(fluid.contains("material.albedo = float3(1.0);"),
            "the interface carries no tint; colouring it too would filter the water twice");
        assertTrue(fluid.contains("material.sourceFlags |= MATERIAL_SOURCE_FLUID;"),
            "the medium boundaries are recognised by this tag");

        String fallback = block(surface, "float3 surfaceTransmittance(SurfaceSample surface, float3 direction) {");
        assertTrue(fallback.contains("waterBiomeTint(") && fallback.contains("* 0.85"),
            "a caller without medium state still gets the legacy tinted sheet");

        String interfaceOnly = block(surface, "float3 surfaceInterfaceTransmittance(SurfaceSample surface, float3 direction) {");
        assertTrue(interfaceOnly.contains("return float3(1.0 - fresnel);"),
            "the medium-aware filter keeps only the Fresnel share");
        assertFalse(interfaceOnly.contains("pow("), "no Beer-Lambert on the interface: the distance term is the medium's");
    }

    @Test
    void shadowRaysCrossTheBodyTheReceiverStandsIn() {
        String tracer = read(TRACER);
        String term = block(tracer, "float3 pathTraceDirectTerm<T : ISceneTracer>");
        assertTrue(term.contains("WaterMedium medium"), "the direct term needs the receiver's body");
        assertTrue(term.contains("traceShadowVisibilityMedium("), "the shadow ray must be traced with it");
        assertFalse(tracer.contains("traceShadowVisibility("),
            "path_tracer must not use the medium-blind shadow walk anywhere");

        String visibility = read(VISIBILITY);
        String walk = block(visibility, "SurfaceVisibility traceFilteredVisibilityMedium<T : ISceneTracer>");
        assertTrue(walk.contains("waterSegmentTransmittance(medium, max(hit.t - lastT, 0.0))"),
            "the column between the receiver and the blocker is attenuated");
        assertTrue(walk.contains("surfaceInterfaceTransmittance("),
            "fluid interfaces in the walk are Fresnel-only; the medium supplies the colour");
        assertFalse(walk.contains("waterInScatter("),
            "a shadow ray reports transmittance, it must not add in-scattered light");

        String blind = block(visibility, "SurfaceVisibility traceFilteredVisibility<T : ISceneTracer>");
        assertFalse(blind.contains("WaterMedium"), "the medium-blind walk keeps its old signature");
        assertTrue(blind.contains("surfaceTransmittance("), "and its legacy tinted filter");
    }

    @Test
    void theBodyTogglesOnlyOnARefractionThroughAHorizontalFluidFace() {
        String tracer = read(TRACER);
        String toggle = block(tracer, "if ((surface.material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u");
        assertTrue(toggle.contains("bsdf.lobe == BSDF_LOBE_TRANSMISSION"),
            "a reflection, including total internal reflection, stays on its own side");
        assertTrue(toggle.contains("waterEntersMedium(medium.inside, faceY, bsdf.direction.y)"),
            "entry is decided by the transmitted direction that crosses the face");
        assertTrue(toggle.contains("waterLeavesMedium(medium.inside, faceY, bsdf.direction.y)"), "and so is the exit");
        assertTrue(toggle.contains("PATH_TRACE_FLAG_WATER_MEDIUM"), "the frame can tell that the fluid was recognised");

        String enter = block(read(WATER), "bool waterEntersMedium(bool inside, float faceNormalY, float directionY) {");
        assertTrue(enter.contains("abs(faceNormalY) > 0.5"),
            "only horizontal faces separate air from the body; flowing sides stay thin sheets");
        assertTrue(enter.contains("directionY < 0.0") && enter.contains("!inside"),
            "entry is downward motion while outside");
        String leave = block(read(WATER), "bool waterLeavesMedium(bool inside, float faceNormalY, float directionY) {");
        assertTrue(leave.contains("directionY > 0.0") && leave.contains("inside"),
            "exit is upward motion while inside");
    }

    @Test
    void everySegmentInsideTheBodyIsAbsorbed() {
        String tracer = read(TRACER);
        assertTrue(tracer.contains("float segment = max(hit.t - mediumStart, 0.0);"),
            "the segment runs from where the last one ended, not from the ray origin");
        assertTrue(tracer.contains("throughput *= waterSegmentTransmittance(medium, segment);"),
            "the segment absorbs what the path continues with");
        assertTrue(tracer.contains("result.radiance += throughput * waterInScatter(medium, midpoint.y, waterDown, segment);"),
            "and scatters the light it received while the throughput was higher");
        assertTrue(tracer.contains("mediumStart = -options.rayBias;"),
            "a cutout advance moves the origin, so the candidate sits rayBias behind it");
        assertTrue(tracer.contains("waterDown = environment.radiance(float3(0.0, 1.0, 0.0));"),
            "the downwelling light is the sky the body sees from above");
        assertTrue(tracer.contains("float tail = clamp(ray.tMax - mediumStart, 0.0, 48.0);"),
            "a ray inside the body that escapes is still absorbed, bounded like the legacy tail");
    }

    // ---- double-precision port of the Slang medium, as in RtWaterMediumTest ----

    private static double[] sigmaT(double[] tint) {
        double[] clear = {CLEAR_R, CLEAR_G, CLEAR_B};
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double t = Math.max(0.02, Math.min(1.0, tint[i]));
            double dye = Math.max(0.0, Math.min(0.20, 0.05 * -Math.log(t)));
            out[i] = clear[i] + dye + SCATTERING;
        }
        return out;
    }

    private static double[] transmittance(double[] tint, double len) {
        double[] st = sigmaT(tint);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = Math.exp(-st[i] * len);
        }
        return out;
    }

    private static double[] segment(double[] tint, double depth, double len, double ambient) {
        double[] st = sigmaT(tint);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double t = Math.exp(-st[i] * len);
            double column = FLOOR + (1.0 - FLOOR) * Math.exp(-st[i] * depth * DEPTH_FALLOFF);
            out[i] = ambient * (SCATTERING / st[i]) * column * (1.0 - t);
        }
        return out;
    }

    private static double luminance(double[] c) {
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static String block(String source, String header) {
        int at = source.indexOf(header);
        assertTrue(at >= 0, "missing block: " + header);
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
        throw new AssertionError("unbalanced block: " + header);
    }

    private static String read(String relative) {
        try {
            return Files.readString(Path.of(relative), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new AssertionError("cannot read " + relative, e);
        }
    }
}
