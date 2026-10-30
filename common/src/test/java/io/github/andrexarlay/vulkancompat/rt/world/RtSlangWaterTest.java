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
 * The Slang path owns the whole water model now: {@code surface/water.slang} is the wave field, the fluid test, the
 * biome tint, the volumetric medium and the light beam a lamp sends into the body. The legacy decoder that these
 * numbers were read out of is deleted, so this suite is the only thing that keeps the port honest about physics.
 * <p>
 * The owner's rule for the two styles is narrow and easy to break by accident: <em>realistic means the waves, not a
 * different water</em>. So the switch may scale the analytic tilt and the sheen roughness that follows from it, and
 * may reach nothing else - not the absorption, not the index of refraction, not the étendue weight of the beam.
 * <p>
 * Ways this silently breaks: a retuned wave table that stops conserving slope variance, a dye term folded into the
 * clear-water absorption so a swamp stops being tinted and starts being opaque, an entry test that lets a ray change
 * medium through a side face of flowing water, a beam column computed from the sight line instead of the refracted
 * path, or the caustics switch taking the étendue ratio with it - which is flux conservation, not an effect.
 */
class RtSlangWaterTest {

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String WATER = SHADERS + "/surface/water.slang";
    private static final String SURFACE = SHADERS + "/surface/surface.slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";
    private static final String ENTRY = SHADERS + "/pathtrace/path_trace_entry.slang";
    private static final String FRAME = SHADERS + "/contracts/frame.slang";
    private static final String AUTO_PBR =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/material/AutoPbr.java";
    private static final String OPTICAL =
        "src/main/java/io/github/andrexarlay/vulkancompat/rt/material/OpticalMaterials.java";

    /** The fluid branch is the third test of the translucency bit, so its header has to name the fluid test too. */
    private static final String FLUID_BRANCH =
        "if ((hit.flags & HIT_FLAG_TRANSLUCENT) != 0u\n                && waterIsSurface(true,";

    /** Six deep-water components, dispersed as gravity waves and filtered by the pixel footprint. */
    @Test
    void theWaveFieldIsTheSixComponentDeepWaterSum() {
        String slang = read(WATER);
        assertTrue(slang.contains("static const int WATER_WAVES = 6;"),
            "six directions: long swells carry the slope, short ripples stay below the shimmer threshold");
        assertEquals(6, count(slang.substring(slang.indexOf("static const float4 WATER_WAVE_A"),
            slang.indexOf("static const float WATER_WAVE_PHASE")), "float4("),
            "the table has exactly WATER_WAVES rows: a seventh wave needs the constant moved too");
        assertTrue(slang.contains("static const float WATER_WAVE_PHASE[WATER_WAVES] = { 0.0, 1.3, 2.9, 4.1, 5.2, 0.7 };"),
            "one phase per wave, so the pattern is not a standing interference");
        assertTrue(slang.contains("float omega = 0.9 * sqrt(k);"),
            "deep-water dispersion: the phase speed is sqrt(g k), so a short ripple moves slower than a swell");
        assertTrue(slang.contains("float attenuation = 1.0 - smoothstep(0.25, 1.0, k * footprint);"),
            "a wave the footprint cannot resolve fades out instead of aliasing into noise at distance");
        assertTrue(slang.contains("lostVar += 0.5 * w.w * w.w * (1.0 - attenuation * attenuation);"),
            "the filtered waves are not gone: their variance is what the sheen roughness is made of");
        assertTrue(slang.contains("static const float WATER_PIXEL_ANGLE = 0.0016;"),
            "the footprint comes from an angle per pixel, so the filter does not depend on the resolution");
        assertTrue(slang.contains("if (abs(geometricNormal.y) < 0.7) {"),
            "a vertical side of a water column stays flat: the wave field is a surface, not a volume");
        assertTrue(slang.contains("xz -= downhill / flow * (time * min(flow * 10.0, 1.6));"),
            "flowing water carries its pattern downhill at a bounded speed, or a waterfall freezes");
    }

    /** The owner's rule: the style switch is a wave slider, and a slider that changes the physics is a different bug. */
    @Test
    void theStyleSwitchMovesOnlyTheWaves() {
        String slang = read(WATER);
        assertTrue(slang.contains("static const float WATER_WAVE_MIX_VANILLA = 0.55;"),
            "the damped style keeps 55% of the tilt: visible ripples, not a storm, and not a mirror");
        assertTrue(slang.contains("if (waveMix >= 1.0) {"),
            "the realistic style returns the analytic normal untouched instead of lerping toward it");
        assertTrue(slang.contains("return safeNormalize(lerp(geometricNormal, wave, max(waveMix, 0.0)), geometricNormal);"),
            "the mix leans the face back toward the geometric normal: one surface, two strengths");

        // Nothing that decides what water *is* may read the switch. Each of these owns a piece of the light budget.
        for (String function : new String[] {"float3 waterSigmaT(float3 tintIn) {",
            "WaterMedium waterMediumFrom(float3 tintIn, float surfY) {",
            "float3 waterSegmentTransmittance(WaterMedium medium, float length) {",
            "float3 waterInScatter(WaterMedium medium, float segmentMidY, float3 downRadiance, float length) {",
            "float3 waterBeamTravel(float3 lightDirection, float3 faceNormal) {",
            "float3 waterBeamTransmittance(float airCosine) {"}) {
            assertFalse(block(slang, function).contains("waveMix"),
                function + " reads the style switch: the absorption, the medium or the crossing would then depend"
                    + " on a slider whose whole job is the wave height");
        }

        String entry = read(ENTRY);
        assertTrue(entry.contains("materialOptions.waterWaveMix = frameWaterRealisticWaves(f) ? 1.0 : WATER_WAVE_MIX_VANILLA;"),
            "the switch reaches the shader as exactly one number, and both of its values are waves-only");
        assertTrue(read(FRAME).contains("return (frame.frame.w & FRAME_FLAG_WATER_REALISTIC) != 0u;"),
            "the frame word is the host's verdict; the shader must not re-read a player setting");

        String fluid = block(read(SURFACE), FLUID_BRANCH);
        assertTrue(fluid.contains("waterStyledWaveNormal(hit.worldPosition, geometricNormal,"),
            "the decoder asks the same test the atlas tag is written for, then waves the face: two answers"
                + " would mean water that refracts but does not move");
        assertTrue(fluid.contains("options.waterWaveMix, lostVar);"),
            "the fluid face is the only consumer of the mix inside the decoder");
        assertTrue(fluid.contains("material.ior = WATER_IOR;"),
            "the traced face and the beam use the same index: two IORs would refract the shading and the light apart");
        assertTrue(fluid.contains("material.roughness = options.waterWaveMix >= 1.0"),
            "and the sheen roughness is the second half of the same wave decision");
        assertTrue(fluid.contains("material.f0 = float3(WATER_INTERFACE_F0);"),
            "the face the shader shades and the face the beam crosses pay one Fresnel");
    }

    @Test
    void theSheenRoughnessComesFromTheFilteredWaveVariance() {
        String surface = read(SURFACE);
        assertTrue(surface.contains("? clamp(sqrt(max(lostVar, 0.0)) * 1.6, 0.02, 0.30)"),
            "realistic: the unresolved ripples become a rougher sheen, bounded so far water is not a mirror or fog");
        assertTrue(surface.contains(": clamp(0.02 + sqrt(max(lostVar, 0.0)) * 0.8, 0.02, 0.16);"),
            "damped: a gentler floor and a tighter ceiling, so the flat sheet reads as calmer and not as a different material");
        assertTrue(surface.contains("float footprint = hit.t * WATER_PIXEL_ANGLE /"),
            "the filter width is the pixel footprint at this distance: far water fades to a sheen instead of sparkling");
        assertTrue(surface.contains("max(abs(dot(rayDirection, geometricNormal)), 0.06)"),
            "a grazing ray would otherwise divide by nearly zero and make the footprint enormous");
    }

    /** Absorption is per block, red first; the dye is a separate term so a biome tints without thickening the water. */
    @Test
    void theMediumKeepsTheAbsorptionCurveAndTheDyeSeparate() {
        String slang = read(WATER);
        assertTrue(slang.contains("static const float3 WATER_CLEAR_ABSORPTION = float3(0.17, 0.036, 0.013);"),
            "clear water: red is absorbed an order of magnitude faster than blue, which is what makes depth blue");
        assertTrue(slang.contains("static const float WATER_SCATTERING = 0.055;"),
            "a nearly grey scattering term gives the body something to show once the bottom has attenuated away");
        assertTrue(slang.contains("static const float WATER_MULTIPLE_SCATTER_FLOOR = 0.65;"),
            "without the floor the in-scatter dies with depth and the ocean turns black below a few blocks");
        assertTrue(slang.contains("float3 dye = clamp(0.05 * (-log(tint)), float3(0.0), float3(0.20));"),
            "the dye is log of the biome tint and is capped: a dark tint may colour the water, never make it a wall");
        assertTrue(slang.contains("return WATER_CLEAR_ABSORPTION + dye + float3(WATER_SCATTERING);"),
            "extinction is the sum, so the tint never removes the clear-water curve under it");
        assertTrue(slang.contains("float3 tint = clamp(tintIn, float3(0.02), float3(1.0));"),
            "a zero channel would make -log(tint) infinite and one sample could black out the biome");
        assertTrue(slang.contains("medium.scatterAlbedo = float3(WATER_SCATTERING) / medium.sigmaT;"),
            "albedo is the scattering share of extinction: the same photons cannot be counted as scattered twice");
        assertTrue(slang.contains("return medium.inside ? exp(-medium.sigmaT * max(length, 0.0)) : float3(1.0);"),
            "Beer-Lambert per channel over the clamped segment length, and a ray outside a body changes nothing");
        assertTrue(slang.contains("+ (1.0 - WATER_MULTIPLE_SCATTER_FLOOR) * exp(-medium.sigmaT * depth * 0.65);"),
            "the sky term decays with the depth of the segment midpoint, so a column darkens instead of one flat tint");
        assertTrue(slang.contains("return downRadiance * medium.scatterAlbedo * column * (float3(1.0) - transmittance);"),
            "in-scatter is the light removed from the ray over this segment: energy-conserving with the transmittance above");
    }

    @Test
    void aBodyIsEnteredOnlyThroughAHorizontalFace() {
        String slang = read(WATER);
        assertTrue(slang.contains("return abs(faceNormalY) > 0.5 && directionY < 0.0 && !inside;"),
            "the sides of flowing water are thin sheets: a ray crossing one must not claim a new body");
        assertTrue(slang.contains("return abs(faceNormalY) > 0.5 && directionY > 0.0 && inside;"),
            "and leaving is the same face with the opposite direction, so the medium cannot latch onto a side");
        assertTrue(slang.contains("WaterMedium noWaterMedium() {"),
            "the neutral medium is a value, not an absent one: the caller always has a valid struct to carry");
        assertTrue(slang.contains("medium.inside = false;"), "and it reads as outside");
        assertTrue(slang.contains("if (!medium.inside) return float3(0.0);"),
            "a segment outside any body scatters nothing, so land light never picks up a water tint");
    }

    /** The shader's fluid test and the host's atlas tag are one decision made twice; if they drift, water stops being water. */
    @Test
    void theFluidTestMatchesTheAtlasTagThatNamesTheFluid() {
        String slang = read(WATER);
        assertTrue(slang.contains("return translucent && luminance(f0) <= 0.025 && roughness <= 0.035 && metallic < 0.5;"),
            "a fluid is the translucent surface the atlas tagged with water's reflectance and roughness: glass (f0 0.04)"
                + " and ice (roughness 0.05) have to stay panes");
        assertTrue(slang.contains("static const float WATER_INTERFACE_F0 = 0.02;"),
            "the interface reflectance the shader charges a crossing beam");
        assertTrue(slang.contains("static const float WATER_IOR = 1.333;"),
            "clear water's index of refraction");

        String autoPbr = read(AUTO_PBR);
        assertTrue(autoPbr.contains("return new Material(0.02f, 0.02f, false, 0f, Emission.NONE, RULE_WATER);"),
            "the host rule for water_ is f0 0.02 and roughness 0.02: the exact pair the shader test above accepts");
        String optical = read(OPTICAL);
        assertTrue(optical.contains("return new Profile(kind == 2 ? 1.333f : kind == 3 ? 1.31f : 1.52f, 1f, 1f, 0f,"),
            "the raster-side optical profile uses the same 1.333: a third IOR would bend the traced beam and the"
                + " screen-space one differently");
        assertTrue(slang.indexOf("WATER_IOR = 1.333") > 0 && optical.contains("1.333f"),
            "both sides of the split still name 1.333");
    }

    @Test
    void theBiomeTintDividesTheBakedFaceShadeBackOut() {
        String slang = read(WATER);
        assertTrue(slang.contains("return normal.y * normal.y * (normal.y > 0.0 ? 1.0 : 0.5)"),
            "the underside of a sheet is baked at half brightness in the vertex colour");
        assertTrue(slang.contains("normal.x * normal.x * 0.6 + normal.z * normal.z * 0.8;"),
            "and the cardinal sides differ, exactly like the vanilla mesher shades them");
        assertTrue(slang.contains("return clamp(vertexColor / max(cardinalFaceShade(geometricNormal), 0.5),"),
            "the tint is the vertex colour with that shade divided out, and the divisor is floored so a face"
                + " cannot amplify the colour into a white-out");
    }

    /** The beam is the only defensible answer for a plane interface: no trace, one direction, one crossing point. */
    @Test
    void theUnderwaterBeamFollowsTheRefractedPathAndStaysInsideTheSample() {
        String slang = read(WATER);
        assertTrue(slang.contains("float3 flat = refract(-lightDirection, float3(0.0, 1.0, 0.0), 1.0 / WATER_IOR);"),
            "air into water uses 1/eta as the refract ratio; the sign is the beam's travel direction, which is"
                + " opposite the receiver-to-light direction");
        assertTrue(slang.contains("if (dot(faceNormal, lightDirection) <= 0.0) return flat;"),
            "a crest that has turned its face away from the beam cannot refract it: the flat sheet answers that one");
        assertTrue(slang.contains("return dot(bent, bent) > 1.0e-8 ? safeNormalize(bent, flat) : flat;"),
            "a zero-length refract result is the total-internal-reflection failure, and it falls back rather than"
                + " normalizing nothing");

        String toLight = block(slang, "WaterBeam waterBeamToLight(");
        assertTrue(toLight.contains("if (!medium.inside || !(distance > 0.0) || !(lightDirection.y > 0.0)) return beam;"),
            "no medium, a light at the receiver or a light below the horizon leaves the beam invalid");
        assertTrue(toLight.contains("if (!(depth > 0.0)) return beam;"),
            "a receiver at or above the recorded surface has no column to cross");
        assertTrue(toLight.contains("if (!(travel.y < -0.05)) return beam;"),
            "a horizontal beam would make the column infinite: only a degenerate sample trips this");
        assertTrue(toLight.contains("float column = depth / -travel.y;"),
            "the column is the beam's length from the receiver up to the face, not the vertical depth");
        assertTrue(toLight.contains("if (!(column > 0.0) || !(column < distance)) return beam;"),
            "the crossing must be nearer than the light: an emitter inside the body never crossed the surface,"
                + " and its straight sight line is the right path");
        assertTrue(toLight.contains("beam.entry = position - travel * column;"),
            "the entry point is where that beam meets the plane, so the focus is measured from the real crossing");
        assertTrue(toLight.contains("beam.direction = travel;"), "and the beam direction is what the shading must use");
        assertTrue(slang.contains("float3 arrival = beam.valid ? -beam.direction : lightDirection;")
            || read(TRACER).contains("float3 arrival = beam.valid ? -beam.direction : lightDirection;"),
            "the path tracer shades along the beam, never along the sight line to the disc");
    }

    /** Étendue is flux conservation: the ratio must be 1 at zenith, drop toward the horizon, and survive the caustics switch. */
    @Test
    void theEtendueWeightIsAConeRatioAndNotAnEffect() {
        String slang = read(WATER);
        assertTrue(slang.contains("beam.solidAngle = max(lightDirection.y, 1.0e-3) / max(-travel.y, 1.0e-3);"),
            "the sample was drawn from air solid angles and is evaluated on the water side, so the weight is"
                + " cos(air) / cos(water) - both eta-squared factors cancel in the flux through one horizontal patch");

        double ior = numberAfter(slang, "static const float WATER_IOR = ");
        assertEquals(1.333, ior, 1e-9, "the parsed constant is what the numbers below are computed from");
        assertEquals(1.0, coneRatio(1.0, ior), 1e-12,
            "straight down the ratio is exactly one: the shader must not invent flux through a flat sheet");
        double grazing = coneRatio(1.0e-3, ior);
        assertTrue(grazing < 1.0e-2, "a grazing sun compresses into the Snell window and lands nearly overhead");
        for (double cosine = 0.05; cosine < 1.0; cosine += 0.05) {
            assertTrue(coneRatio(cosine, ior) <= 1.0 + 1e-12,
                "the water-side cone is never wider than the air-side one for eta > 1");
            assertTrue(coneRatio(cosine, ior) > 0.0, "and it is never zero or signed");
        }
        double window = Math.toDegrees(Math.asin(1.0 / ior));
        assertTrue(window > 46.0 && window < 51.0,
            "the whole sky compresses into a " + window + " deg half-angle window under water; an IOR tuned far"
                + " from 1.333 would move this and with it every underwater shadow direction");

        String tracer = read(TRACER);
        assertTrue(tracer.contains("float beamWeight = beam.valid"), "the bent beam carries its own weight");
        assertTrue(tracer.contains("? beam.solidAngle * (options.waterCaustics"),
            "the étendue ratio is multiplied by the focus only when caustics are on: the ratio is conservation,"
                + " the focus is the effect the switch owns");
        assertTrue(tracer.contains("materialOptions.waterWaveMix) : 1.0)"),
            "and with the focus off the beam still arrives along the refracted path with weight 1");
        assertTrue(tracer.contains("beam = waterBeamToLight(lightDirection, light.distance, position, medium);"),
            "the beam is asked for only of a directional light: a block emitter that never crossed the face"
                + " is not this problem");
    }

    /** The caustic is the Jacobian of the entry-point-to-floor map, read by finite difference through three wave faces. */
    @Test
    void theCausticIsTheJacobianOfTheEntryMap() {
        String slang = read(WATER);
        assertTrue(slang.contains("if (!beam.valid) return 1.0;"),
            "no beam means no gathering: the straight sight line is not focused by a face it never crossed");
        assertTrue(slang.contains("static const float WATER_CAUSTIC_STEP_MIN = 0.25;"),
            "the finite difference is a wave-scale question, so it is clamped in blocks at both ends");
        assertTrue(slang.contains("static const float WATER_CAUSTIC_STEP_MAX = 4.0;"));
        assertTrue(slang.contains("float step = clamp(beam.column * 0.5, WATER_CAUSTIC_STEP_MIN, WATER_CAUSTIC_STEP_MAX);"),
            "the step scales with the depth: a deep floor must not measure ripples that already blurred into a sheen");
        assertTrue(slang.contains("float det = dx.x * dz.y - dx.y * dz.x;"),
            "the area the two neighbouring beams spread over, divided out of the entry square");
        assertTrue(slang.contains("if (!isFinite(det) || det <= 1.0e-3) return WATER_CAUSTIC_MIN;"),
            "a non-positive determinant is a fold: the neighbouring beams crossed, so the reciprocal of a signed"
                + " number is not the flux this receiver gets");
        assertTrue(slang.contains("return clamp(1.0 / det, WATER_CAUSTIC_MIN, WATER_CAUSTIC_MAX);"),
            "0.1 to 3.0 bounds what one sample can push into a history that lives for thousands of frames");
        assertTrue(slang.contains("float2 waterBeamAtDepth(float2 entryXZ, float entryY, float receiverY,"),
            "the map is evaluated through the styled wave normal, so damping the waves damps the focus with them");
        assertTrue(slang.contains("return entryXZ + travel.xz * ((entryY - receiverY) / max(-travel.y, 1.0e-3));"),
            "the footprint is the beam walked down to the receiver's height");
    }

    /** cos(air) / cos(water) for a flat sheet, with refraction solved the same way the shader solves it. */
    private static double coneRatio(double airCosine, double ior) {
        double airSine = Math.sqrt(Math.max(0.0, 1.0 - airCosine * airCosine));
        double waterSine = airSine / ior;
        double waterCosine = Math.sqrt(Math.max(0.0, 1.0 - waterSine * waterSine));
        return airCosine / waterCosine;
    }

    private static double numberAfter(String source, String prefix) {
        int at = source.indexOf(prefix);
        assertTrue(at >= 0, "missing constant: " + prefix);
        int end = at + prefix.length();
        while (end < source.length() && (Character.isDigit(source.charAt(end)) || source.charAt(end) == '.')) {
            end++;
        }
        return Double.parseDouble(source.substring(at + prefix.length(), end));
    }

    private static int count(String source, String needle) {
        int hits = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            hits++;
        }
        return hits;
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
