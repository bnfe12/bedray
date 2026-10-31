package io.github.andrexarlay.vulkancompat.rt.world;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key light reaches an underwater receiver along the path it took: bent where it crossed the fluid face, down the
 * column to the surface plane, and out through the air toward the disc. The straight sight line from the receiver to
 * that disc is a different line, so before the beam existed an underwater floor was lit from the wrong direction,
 * through a column that was not the one the light crossed, with no loss at the crossing and no caustics.
 * <p>
 * A plane interface has a closed-form answer, so this test can hold the estimator against Snell and étendue in double
 * precision: a flat sheet must hand the floor exactly the flux the sheet let through, the beam can never arrive
 * shallower than the critical angle, and the wave focus must be the area the bundle spreads over - not its extent, not
 * its reciprocal.
 * <p>
 * Ways this silently breaks: the index ratio flipped on the way in (the window opens the wrong way and the sun slides
 * under the underwater horizon), the solid-angle weight inverted (a floor under a low sun reads brighter than the
 * surface transmits), the sky leg started on the sheet instead of above it (the water face pays its Fresnel twice), the
 * beam offered to a block emitter that never crossed the surface (a torch under water lights the floor through a
 * column it did not enter), or the focus trusted across a fold of the map (a caustic line one pixel wide, kept for
 * thousands of frames).
 */
class RtSlangWaterBeamTest {

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String WATER = SHADERS + "/surface/water.slang";
    private static final String SURFACE = SHADERS + "/surface/surface.slang";
    private static final String VISIBILITY = SHADERS + "/surface/visibility.slang";
    private static final String TRACER = SHADERS + "/pathtrace/path_tracer.slang";

    /**
     * Air into water through a plane face: Snell bends the beam toward the normal, and the whole sky ends up inside the
     * cone of the critical angle. That is what makes the sun overhead rather than on the horizon, and what bounds how
     * much column a given depth can cost.
     */
    @Test
    void theRefractedBeamAlwaysArrivesFromAbove() {
        double ior = shaderFloat(WATER, "WATER_IOR");
        double windowCosine = Math.sqrt(1.0 - 1.0 / (ior * ior));
        assertTrue(windowCosine > 0.65 && windowCosine < 0.67,
            "the Snell window at water's index is 48.6 degrees wide, so no underwater beam is much shallower than 42 "
                + "degrees above the floor; the number the model rests on moved");

        for (double degrees = 1.0; degrees <= 89.0; degrees += 2.0) {
            double airCosine = Math.cos(Math.toRadians(degrees));
            double airSine = Math.sqrt(Math.max(0.0, 1.0 - airCosine * airCosine));
            double[] travel = refractDown(airCosine, ior);
            assertEquals(1.0, Math.sqrt(travel[0] * travel[0] + travel[1] * travel[1] + travel[2] * travel[2]), 1.0e-12,
                "refract stays unit at " + degrees + " degrees, like the shader's");
            assertTrue(travel[1] < 0.0, "the beam travels down into the body at " + degrees + " degrees");
            assertEquals(airSine / ior, -travel[0], 1.0e-12,
                "Snell on the tangential component at " + degrees + " degrees, which is what the built-in must give");
            assertEquals(0.0, travel[2], 1.0e-12, "the beam stays in the plane the sun rose in");
            assertTrue(-travel[1] >= windowCosine - 1.0e-12,
                "the beam is inside the window at " + degrees + " degrees, got cos " + (-travel[1]));
            assertTrue(-travel[1] > airCosine,
                "entering the dense side bends toward the normal, so the sun is higher under water than in air");
        }
        assertTrue(1.0 / windowCosine < 1.52,
            "a column is at most 1.5 times its own depth, which is what keeps the absorption bounded");
    }

    /**
     * The estimator samples air solid angles and evaluates the BSDF on the water-side direction, so it needs the ratio
     * between the two. Radiance rises by (n_water/n_air) squared entering the denser side while the cone narrows by the
     * same factor times the cosine ratio; the indices cancel and cos(air)/cos(water) is what survives. A horizontal
     * floor then reads exactly the flux the sheet let through, which is the check below.
     */
    @Test
    void theEstimatorKeepsTheFluxTheSurfaceLetThrough() {
        String beamBlock = block(read(WATER), "WaterBeam waterBeamToLight(");
        assertTrue(beamBlock.contains("beam.solidAngle = max(lightDirection.y, 1.0e-3) / max(-travel.y, 1.0e-3);"),
            "the weight is the air cosine over the water cosine, in that order: the port below is what says so");

        double ior = shaderFloat(WATER, "WATER_IOR");
        for (double degrees = 5.0; degrees <= 85.0; degrees += 5.0) {
            double airCosine = Math.cos(Math.toRadians(degrees));
            double waterCosine = -refractDown(airCosine, ior)[1];
            double etendue = beamWeight(airCosine, waterCosine, ior);
            assertEquals(airCosine / waterCosine, etendue, 1.0e-12,
                "at " + degrees + " degrees the two physical factors must cancel to the cosine ratio the shader uses");
            // bsdfEvaluate carries the receiver's own cosine; a horizontal floor measures the beam with cos(water).
            assertEquals(airCosine, waterCosine * etendue, 1.0e-12,
                "a floor under a flat sheet gets the irradiance the sheet let in, which is the only way the water "
                    + "surface is not a free brightness boost");
            assertTrue(etendue < 1.0, "the cone narrows entering water, so an air-side pdf is divided, not multiplied");
        }
    }

    /** {@code (radiance gain into the dense side) x (solid-angle squeeze of the cone)}, from Snell and etendue. */
    private static double beamWeight(double airCosine, double waterCosine, double ior) {
        double eta = 1.0 / ior;
        double radianceGain = 1.0 / (eta * eta);
        double squeeze = eta * eta * airCosine / waterCosine;
        return radianceGain * squeeze;
    }

    /** The two lines that decide which way the beam bends, and what a degenerate answer must fall back to. */
    @Test
    void theBeamIsBentByTheInterfaceItCrossed() {
        String travel = block(read(WATER), "float3 waterBeamTravel(float3 lightDirection, float3 faceNormal) {");
        assertTrue(travel.contains("refract(-lightDirection, float3(0.0, 1.0, 0.0), 1.0 / WATER_IOR)"),
            "air into water: the ratio is the incident index over the dense one, so it stays under 1 and there is no "
                + "critical angle on the way in");
        assertFalse(travel.contains("), WATER_IOR)"),
            "an inverted ratio bends away from the normal, opens the window the wrong way and lets the sun set under "
                + "the underwater horizon");
        assertTrue(travel.contains("if (dot(faceNormal, lightDirection) <= 0.0) return flat;"),
            "a crest that has turned its face away from the beam is answered by the flat sheet, not by a refract that "
                + "points the light back up");
        assertTrue(travel.contains("dot(bent, bent) > 1.0e-8 ? safeNormalize(bent, flat) : flat"),
            "a degenerate refraction is the flat beam, not a zero direction the column would divide by");

        String beam = block(read(WATER), "WaterBeam waterBeamToLight(");
        assertTrue(beam.contains("if (!(travel.y < -0.05)) return beam;"),
            "a beam that does not travel down has not crossed the surface above the receiver");
        assertTrue(beam.contains("beam.direction = travel;"), "the beam carries the travel direction, and the caller negates it");
    }

    /** The geometry the shader cannot get wrong without the beam leaving the body it was named in. */
    @Test
    void theBeamCrossesTheSurfaceTheMediumRecorded() {
        String beam = block(read(WATER), "WaterBeam waterBeamToLight(float3 lightDirection, float distance, "
            + "float3 position, WaterMedium medium) {");
        assertTrue(beam.contains("if (!medium.inside || !(distance > 0.0) || !(lightDirection.y > 0.0)) return beam;"),
            "no body, no light above the horizon, no beam");
        assertTrue(beam.contains("float depth = medium.surfY - position.y;") && beam.contains("if (!(depth > 0.0)) return beam;"),
            "the plane is the height the medium carries, and a receiver at or above it is not inside that column");
        assertTrue(beam.contains("float column = depth / -travel.y;"), "the column is the depth measured along the beam");
        assertTrue(beam.contains("if (!(column > 0.0) || !(column < distance)) return beam;"),
            "a light nearer than the column never crossed the surface: the straight sight line is its path");
        assertTrue(beam.contains("beam.entry = position - travel * column;"),
            "walking the beam back from the receiver lands on the plane; the other sign lands on the floor below");

        double ior = shaderFloat(WATER, "WATER_IOR");
        for (double degrees = 5.0; degrees <= 85.0; degrees += 10.0) {
            double airCosine = Math.cos(Math.toRadians(degrees));
            double[] travel = refractDown(airCosine, ior);
            double depth = 4.0;
            double column = depth / -travel[1];
            assertEquals(depth, column * -travel[1], 1.0e-12,
                "the column spans exactly the depth at " + degrees + " degrees, which is what puts the entry point on "
                    + "the plane rather than a block above or below it");
            double drift = column * Math.abs(travel[0]);
            double window = Math.tan(Math.asin(1.0 / ior));
            assertEquals(Math.tan(Math.asin(Math.sqrt(1.0 - airCosine * airCosine) / ior)) * depth, drift, 1.0e-9,
                "the beam drifts sideways by the tangent of the refracted angle");
            assertTrue(drift < depth * (window + 1.0e-9),
                "and never further than the Snell window allows, got " + drift + " for a " + depth + "-block column");
        }
    }

    /** The two legs of the walk, and the crossing that neither of them traces. */
    @Test
    void theWalkBendsTheColumnAndKeepsTheSkyStraight() {
        String walk = block(read(VISIBILITY), "SurfaceVisibility traceShadowBeam<T : ISceneTracer>(");
        assertTrue(walk.contains("traceFilteredVisibilityMedium(traversal, atlas, blockMaterials,"),
            "the column leg is the medium-aware walk, so the water between the receiver and the surface absorbs");
        assertTrue(walk.contains("traceFilteredVisibility(traversal, atlas, blockMaterials,"),
            "the sky leg is the plain walk: above the sheet the light is in air and no face toggles anything");
        assertTrue(walk.contains("SURFACE_VISIBILITY_FLAG_REFRACTED"), "the frame can tell a bent beam from a straight ray");
        assertTrue(walk.contains("visibility.layers += air.layers;"),
            "the sky leg spends the same filter budget as the column: a beam that walked sixteen faces above the sheet "
                + "has not proven a sight line either");
        assertTrue(walk.contains("if (!surfaceVisibilityProven(visibility)) {"),
            "a blocker inside the column ends the beam before the sky leg is asked");
        assertTrue(walk.contains("bool skyClear = surfaceVisibilityProven(air);"),
            "the sky leg is proven or it is nothing: a walk that ran out of filters did not reach the sun either");
        assertFalse(walk.contains("air.result.visible"),
            "the raw visibility bit is read by the predicate and by nothing else in the file that owns it");
        assertTrue(walk.contains("float3 entry = beam.entry + beam.air * (bias / max(beam.air.y, 0.1));"),
            "the sky leg is lifted along the beam, not along the receiver's normal, so it starts above the sheet");
        assertTrue(walk.contains("* air.result.transmittance * waterBeamTransmittance(beam.air.y)"),
            "one Fresnel for the crossing, charged at the air-side cosine");
        assertFalse(walk.contains("surfaceInterfaceTransmittance("),
            "the face itself is never traced, so its filter cannot be paid for twice");
        assertFalse(walk.contains("waterInScatter("), "a shadow beam reports transmittance, it adds no light");
        assertFalse(walk.contains("medium.inside = false"),
            "the walk leaves the caller's medium alone: the beam is a sight line, not a path that changes state");
    }

    /** The crossing loss has to agree with the one a traced hit on the same face pays, or the two paths differ in brightness. */
    @Test
    void theBeamAndTheTracedFacePayTheSameCrossing() {
        double f0 = shaderFloat(WATER, "WATER_INTERFACE_F0");
        assertEquals(0.02, f0, 1.0e-12, "water's reflectance at normal incidence, the number the fluid decode hands the interface");
        String surface = block(read(SURFACE), "&& waterIsSurface(true, material.f0");
        assertTrue(surface.contains("material.f0 = float3(WATER_INTERFACE_F0);"),
            "the sheet takes it from the same constant, so a beam and a traced crossing cannot drift apart");
        assertFalse(surface.contains("float3(0.02)"), "no second copy of the number left in the decoder");

        String beam = block(read(WATER), "float3 waterBeamTransmittance(float airCosine) {");
        String traced = block(read(SURFACE), "float3 surfaceInterfaceTransmittance(SurfaceSample surface, float3 direction) {");
        assertTrue(beam.contains("WATER_INTERFACE_F0 + (1.0 - WATER_INTERFACE_F0) * pow5(1.0 - cosine)"),
            "Schlick at the flat face");
        assertTrue(traced.contains("f0 + (1.0 - f0) * pow5(1.0 - cosine)"), "the same Schlick the walk already used");
        assertTrue(beam.contains("clamp(airCosine, 0.05, 1.0)"), "the same floor on the grazing cosine");
        assertTrue(beam.contains("return float3(1.0 - fresnel);") && traced.contains("return float3(1.0 - fresnel);"),
            "reflection only: the colour of the water is the column, not the interface");
    }

    /** Where the beam is offered, and what it replaces in the direct term. */
    @Test
    void theDirectTermShadesTheArrivingBeamNotTheSightLine() {
        String term = block(read(TRACER), "float3 pathTraceDirectTerm<T : ISceneTracer>");
        assertTrue(term.contains("if (medium.inside && (light.kind == LightKind.Sun || light.kind == LightKind.Moon)) {"),
            "only a light that came down through the surface is bent; a block emitter that never crossed it keeps the "
                + "straight column.  Both water styles get the bend - the switch is about waves, not about which "
                + "medium the light is in");
        assertTrue(term.contains("float3 arrival = beam.valid ? -beam.direction : lightDirection;"),
            "the beam travels down, so the direction the receiver sees the light in is its negation");
        assertTrue(term.contains("bsdf = bsdfEvaluate(surface.material, view, normal, arrival);"),
            "the lobe is asked about the direction the light arrived from, which is what moves a shadow under water");
        assertFalse(term.contains("bsdfEvaluate(surface.material, view, normal, lightDirection)"),
            "the air-side sight line is not an underwater arrival direction");
        assertTrue(term.contains("float cosine = max(dot(normal, arrival), 0.0);"), "and neither is it the horizon test");
        assertTrue(term.contains("traceShadowBeam(traversal, atlas, blockMaterials, shadowPosition, shadowNormal, lightDirection,")
                && term.contains("traceShadowVisibilityMedium(traversal, atlas, blockMaterials, shadowPosition, shadowNormal,"),
            "both walks stay: the beam when the surface was crossed, the straight column when it was not");
        assertTrue(term.contains("float3 shadowPosition = position;"),
            "the walks start at the receiver; only the back-lit leaf rule may move the origin, and it never fires "
                + "under water where the bent column carries the light");
        assertTrue(term.contains("* beamWeight / max(light.sourcePdf, 1.0e-6)"),
            "the estimator weight has to ride on the result or the bend changes the brightness instead of its place");
        assertFalse(term.contains("return bsdf * light.radiance * transmittance / max(light.sourcePdf, 1.0e-6);"),
            "no unweighted return left in the direct term");
    }

    /**
     * The wave field focuses the beam, and the focus is the area the bundle spreads over between the surface and the
     * receiver. Three refractions read the 2x2 map; no ray is traced, so the cost is a handful of transcendentals on
     * the pixels that are already under water.
     */
    @Test
    void theCausticIsTheAreaTheBundleSpreadsOver() {
        String water = read(WATER);
        String focus = block(water, "float waterBeamFocus(WaterBeam beam, float3 position, float3 lightDirection, "
            + "float time,");
        assertTrue(focus.contains("float step = clamp(beam.column * 0.5, WATER_CAUSTIC_STEP_MIN, WATER_CAUSTIC_STEP_MAX);"),
            "the ripple that matters at depth is about as wide as the depth, so the sample step scales with the column");
        assertEquals(3, count(focus, "waterBeamAtDepth("),
            "a centre and two neighbours: the map from the surface to the floor is 2x2, one neighbour would read a curve");
        assertTrue(focus.contains("float det = dx.x * dz.y - dx.y * dz.x;"),
            "the determinant of that map - a length or a dot product would not be area");
        assertTrue(focus.contains("if (!isFinite(det) || det <= 1.0e-3) return WATER_CAUSTIC_MIN;"),
            "a fold has crossed its own neighbours over; its reciprocal is not a brightness");
        assertTrue(focus.contains("return clamp(1.0 / det, WATER_CAUSTIC_MIN, WATER_CAUSTIC_MAX);"),
            "convergence is the reciprocal of the area, and it is bounded");
        assertFalse(focus.contains("trace"), "the focus costs refractions and wave normals, never a ray");
        assertFalse(focus.contains("rand"), "a caustic that flickers with the pixel seed is noise, not the wave field");

        String atDepth = block(water, "float2 waterBeamAtDepth(float2 entryXZ, float entryY, float receiverY, "
            + "float3 lightDirection,");
        assertTrue(atDepth.contains("waterStyledWaveNormal("),
            "the face the beam enters is the wavy one, at whatever strength the player's style leaves it");
        assertTrue(atDepth.contains("float3(0.0, 1.0, 0.0)"), "measured on a horizontal sheet, from above");
        assertTrue(atDepth.contains("entryXZ + travel.xz * ((entryY - receiverY) / max(-travel.y, 1.0e-3))"),
            "the footprint is where the beam has fallen to the receiver's depth");

        double min = shaderFloat(WATER, "WATER_CAUSTIC_MIN");
        double max = shaderFloat(WATER, "WATER_CAUSTIC_MAX");
        assertEquals(1.0, determinantOf(1.0, 0.0, 0.0, 1.0), 1.0e-12, "a flat sheet maps a square onto the same square");
        assertEquals(0.25, determinantOf(0.5, 0.0, 0.0, 0.5), 1.0e-12, "a dimple that gathers four squares onto one");
        assertEquals(1.0, determinantOf(1.0, 0.5, 0.0, 1.0), 1.0e-12, "a shear slides the bundle without landing more of it");
        assertTrue(determinantOf(1.0, 2.0, 0.0, -0.5) < 0.0, "a fold: the neighbours crossed over each other");
        assertEquals(max, clamp(1.0 / 0.25, min, max), 1.0e-12, "the strongest focus the shader allows");
        assertEquals(0.25, clamp(1.0 / 4.0, min, max), 1.0e-12, "a bundle spread over four areas keeps a quarter of its flux");
        assertTrue(max <= 3.0, "a bound above three lets one caustic pixel sit in a history for thousands of frames");
        assertTrue(min > 0.0, "a focus of zero would black out a line of floor the beam only grazed");
    }

    /** The shader's stencil, with the linear map {@code p -> (a*px + b*pz, c*px + d*pz)} in place of the wave field. */
    private static double determinantOf(double a, double b, double c, double d) {
        double step = 0.75;
        double[] east = {a * step, c * step};
        double[] north = {b * step, d * step};
        return (east[0] / step) * (north[1] / step) - (east[1] / step) * (north[0] / step);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }

    /** The call site is the only place the clock and the light pair up, so it is the line that can drift. */
    @Test
    void theBeamUsesTheSameClockAsTheSheetItBendsThrough() {
        String term = block(read(TRACER), "float3 pathTraceDirectTerm<T : ISceneTracer>");
        assertTrue(term.contains("beam.solidAngle * (options.waterCaustics\n"
            + "            ? waterBeamFocus(beam, position, beam.air, materialOptions.portalTime,\n"
            + "                materialOptions.waterWaveMix) : 1.0)"),
            "area times étendue: the focus rides on the same weight the crossing needs, bends through the same scaled "
                + "face the sheet shows, and 1.0 when the player turned the focus off");
        String surface = block(read(SURFACE), "&& waterIsSurface(true, material.f0");
        assertTrue(surface.contains("options.portalTime"), "the wave normal the decoded face uses");
        assertTrue(term.contains("beam = waterBeamToLight(lightDirection, light.distance, position, medium);"),
            "the sample's own distance bounds the column, so an emitter inside the water is not treated as a sun");
    }

    // ---- ports of the shader arithmetic, in double precision ------------------

    /** {@code refract(-lightDirection, up, 1/ior)} for a light {@code airCosine} above the vertical, in the x-y plane. */
    private static double[] refractDown(double airCosine, double ior) {
        double airSine = Math.sqrt(Math.max(0.0, 1.0 - airCosine * airCosine));
        double[] incident = {-airSine, -airCosine, 0.0};
        return refract(incident, new double[] {0.0, 1.0, 0.0}, 1.0 / ior);
    }

    /** HLSL/Slang {@code refract}: the same expression the shader's built-in evaluates. */
    private static double[] refract(double[] incident, double[] normal, double eta) {
        double dot = normal[0] * incident[0] + normal[1] * incident[1] + normal[2] * incident[2];
        double k = 1.0 - eta * eta * (1.0 - dot * dot);
        if (k < 0.0) {
            return new double[] {0.0, 0.0, 0.0};
        }
        double[] out = new double[3];
        for (int c = 0; c < 3; c++) {
            out[c] = eta * incident[c] - normal[c] * (dot * eta + Math.sqrt(k));
        }
        return out;
    }

    private static double shaderFloat(String path, String name) {
        Matcher declaration = Pattern.compile("static const float " + name + " = ([0-9.e-]+);").matcher(read(path));
        assertTrue(declaration.find(), name + " must be one float literal in " + path);
        return Double.parseDouble(declaration.group(1));
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
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
