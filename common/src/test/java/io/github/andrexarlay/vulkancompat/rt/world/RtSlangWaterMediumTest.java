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
 * The typed path tracer carries the water body as a medium: a refracted transmission through a horizontal fluid face
 * enters or leaves it, every segment inside is attenuated by the body's extinction, and each segment adds the light it
 * scatters towards the eye. The eye-visible colour of water is therefore distance, not a tint painted on the interface.
 * <p>
 * {@link RtWaterMediumTest} pins the legacy GLSL reference numerics; this guard keeps the Slang port on the same
 * numbers and keeps the places that can silently break the model shut: a tinted interface (double filter), a shadow ray
 * that ignores the body (underwater surfaces lit through clear air), a boundary toggle that fires on a reflection
 * instead of a refraction (the path would think it left the water on a mirror bounce), and an eye that starts inside a
 * body and crosses no surface at all, so only the host can say which medium its first segment runs in.
 */
class RtSlangWaterMediumTest {

    private static final String SHADERS = "src/main/resources/shaders/slang";
    private static final String JAVA = "src/main/java/io/github/andrexarlay/vulkancompat/rt/world";
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

    /**
     * A camera that starts under water crosses no surface, so nothing in the geometry tells the path it begins inside a
     * body: the host names the medium and the walk up from the eye finds which body. Without this every primary segment
     * runs through clear air, and the deeper the swimmer goes the less the image changes.
     */
    @Test
    void theEyeStartsInsideItsOwnWaterBody() {
        String tracer = read(TRACER);
        String walk = block(tracer, "WaterMedium primaryWaterMedium<T : ISceneTracer>(");
        assertTrue(walk.contains("traversal.traceClosest(makeRay(origin, up, 0.0, remaining))"),
            "the probe is a geometric walk, not the eye's own primary ray with its first-person shortcuts");
        assertTrue(walk.contains("(body.material.sourceFlags & MATERIAL_SOURCE_FLUID) != 0u")
                && walk.contains("body.hit.geometricNormal.y > 0.5"),
            "the top face of a fluid is the boundary, the same face a diving ray enters through");
        assertTrue(walk.contains("return waterMediumFrom(waterBiomeTint(face.tint, body.hit.geometricNormal),")
                && walk.contains("body.hit.position.y);"),
            "the body is the one the surface decoder would name, at the height of the face it hit");
        assertTrue(walk.contains("return noWaterMedium();"), "a walk that finds no face reports no medium");
        assertTrue(walk.contains("if (!body.valid || surfaceIsHole(body, alphaCutoff)) {"),
            "a cutout is not a ceiling: stopping here would report no surface above a submerged plant");

        String seed = block(tracer, "if (options.primaryInWater) {");
        assertTrue(seed.contains("primaryWaterMedium(traversal, atlas, blockMaterials, materialOptions,")
            && seed.contains("primaryRay.origin"), "the walk starts at the eye, not at the pixel's surface");
        assertTrue(seed.contains("medium = waterMediumFrom(float3(1.0), primaryRay.origin.y);"),
            "the host has already named the medium, so a walk that missed still absorbs, at the shallowest depth "
                + "that cannot invent column the body does not have");
        assertTrue(seed.contains("waterDown = environment.radiance(float3(0.0, 1.0, 0.0));"),
            "in-scatter needs the downwelling light before the first segment, so it cannot wait for an entry");
        assertTrue(seed.contains("result.flags |= PATH_TRACE_FLAG_WATER_MEDIUM;"),
            "an underwater frame is tellable from the flags the host already reads");
        assertFalse(tracer.contains("options.flags & PATH_TRACE_OPTION_CAMERA_IN_WATER"),
            "PathTracePush.flags mirrors a host ABI bit for bit; a shader-only verdict must not join it");

        String entry = read(SHADERS + "/pathtrace/path_trace_entry.slang");
        assertTrue(entry.contains("options.primaryInWater = frameCameraInWater(f);"),
            "both path_trace entries share this file, so a fix here cannot miss the native-scene pass");
    }

    /**
     * The optical lobe refracted from air into the material only, so a ray that left no surface (an underwater eye) bent
     * the wrong way and never totally internally reflected at the surface it was about to cross.
     */
    @Test
    void theOpticalLobeRefractsFromTheMediumTheRayTravelsIn() {
        String bsdf = read(SHADERS + "/shading/bsdf.slang");
        String sample = block(bsdf,
            "BSDFSample bsdfSample(MaterialSample material, float3 V, float3 N, float incidentIor,");
        assertTrue(sample.contains("float dense = max(material.ior, 1.0);"),
            "the material's own index is the dense side, and air is the other one");
        assertTrue(sample.contains("float eta = incidentIor >= dense ? incidentIor : incidentIor / dense;"),
            "leaving the dense side keeps the incident index, which is what makes the ratio exceed 1");
        assertFalse(sample.contains("1.0 / max(material.ior, 1.0)"),
            "the air-only ratio cannot say anything about a ray that starts under water");
        assertTrue(sample.contains("if (dot(refracted, refracted) <= 1.0e-8)"), "the total-internal-reflection branch stays");
        assertTrue(sample.contains("result.direction = safeNormalize(reflect(-V, N), N);"),
            "and total internal reflection is a mirror, not a ray that keeps going straight");

        String tracer = read(TRACER);
        assertTrue(tracer.contains("medium.inside ? WATER_IOR : 1.0, seed)"),
            "the lobe is told which side of the interface the ray is on");

        // The physics the two branches have to keep: entering water from air bends toward the normal, a ray already in
        // the body bends away from it and mirrors past the critical angle (Snell at grazing exit), and a denser block
        // under water is still entered rather than left.
        double ior = 1.333;
        assertTrue(eta(1.0, ior) < 1.0, "air to water bends toward the normal, got " + eta(1.0, ior));
        assertEquals(1.0 / ior, eta(1.0, ior), 1.0e-12, "the air side keeps the ratio the legacy path used");
        assertTrue(eta(ior, ior) > 1.0, "water to air bends away from it, which is what lets the surface mirror");
        assertTrue(eta(ior, 1.5) < 1.0, "a diamond under water is still entered, not exited");
        double critical = Math.toDegrees(Math.asin(1.0 / ior));
        assertTrue(critical > 45.0 && critical < 55.0,
            "a swimmer's mirror starts just past 48 degrees; a different index would move that line and the look");
    }

    /** {@code incidentIor >= dense ? incidentIor : incidentIor / dense}, the shader's ratio ported. */
    private static double eta(double incidentIor, double materialIor) {
        return incidentIor >= materialIor ? incidentIor : incidentIor / materialIor;
    }

    /** The host's fluid state is the only ground truth for the eye's own block, so its bit must not drift across the ABI. */
    @Test
    void theHostPublishesTheCamerasFluidState() {
        String contract = read(SHADERS + "/contracts/frame.slang");
        assertTrue(contract.contains("bool frameCameraInWater(FrameConstants frame)"),
            "the pass reads it through a helper, so a renamed bit cannot be missed");
        Matcher shader = Pattern.compile("FRAME_FLAG_CAMERA_IN_WATER\\s*=\\s*1u\\s*<<\\s*(\\d+)u").matcher(contract);
        assertTrue(shader.find(), "the contract must declare FRAME_FLAG_CAMERA_IN_WATER as a single bit");
        int bit = Integer.parseInt(shader.group(1));
        assertTrue(bit >= 5, "bits 0-4 are already taken by the history, cut, reset, first-person and debug flags");
        assertEquals(1 << bit, RtPathTraceSlangPass.Frame.FLAG_CAMERA_IN_WATER,
            "the Java mirror drifted from the shader's bit: the pass would never see water");

        String frameData = read(JAVA + "/RtSlangFrameData.java");
        assertTrue(frameData.contains("frame.flags = p.cameraInWater ? RtPathTraceSlangPass.Frame.FLAG_CAMERA_IN_WATER : 0;"),
            "one conversion for every native pass, or the two path entries could disagree");
        assertFalse(frameData.contains("frame.flags = 0;"), "the flags word has no unconditional zero left to overwrite it");

        String renderer = read(JAVA + "/RtFrameRenderer.java");
        assertTrue(renderer.contains("params.cameraInWater = fogState.medium == 1;"),
            "the verdict comes from the same camera fluid state that already picks the water fog");

        // Two backends, two producers of the frame word: the Slang pass writes it itself, while the native core builds
        // its own FrameConstants from a host bit, so the medium has to reach both or a playtest of the live path shows
        // no water at all.
        String pathPass = read(JAVA + "/RtSlangPathPass.java");
        assertTrue(pathPass.contains("| (params.cameraInWater ? BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_IN_WATER : 0);"),
            "the native backend gets the verdict as a host bit of its own");
        String layouts = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLayouts.java");
        assertTrue(layouts.contains("public static final int PATH_TRACE_FRAME_CAMERA_IN_WATER = 1 << 8;"),
            "bits 0-7 of the host word are taken; the shader's frame word is a separate namespace, and the core maps "
                + "one onto the other");
        assertTrue(layouts.contains("public static final int PATH_TRACE_FEATURE_CAMERA_IN_WATER = 1 << 4;"),
            "the core has to advertise the mapping, or the host would set a bit its DLL rejects outright");

        String nativeTrace = read(JAVA + "/BedrayPathTraceNative.java");
        assertTrue(nativeTrace.contains("if ((flags & BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_IN_WATER) != 0 "
                + "&& !library.hasCameraInWaterFlag()) {"),
            "an older core answers an unknown flag with a rejected frame: the verdict is dropped, not negotiated");
        assertTrue(nativeTrace.contains("flags &= ~BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_IN_WATER;"),
            "and the frame then traces as if the eye were in air, which is what every older build did");

        String coreHeader = read("../native/bedray_core/include/bedray_core.h");
        assertTrue(coreHeader.contains("#define BEDRAY_PATH_TRACE_FRAME_CAMERA_IN_WATER (1u << 8)"),
            "the host bit and the core's declaration must be the same number");
        assertTrue(coreHeader.contains("#define BEDRAY_PATH_TRACE_FEATURE_CAMERA_IN_WATER (1u << 4)"),
            "the mapping is a declared feature, not an undocumented bit");
        String coreSource = read("../native/bedray_core/src/core.cpp");
        assertTrue(coreSource.contains("| BEDRAY_PATH_TRACE_FEATURE_CAMERA_IN_WATER;"),
            "the feature mask has to report what the record validation already accepts");

        String corePass = read("../native/bedray_core/src/path_trace_pass.cpp");
        assertTrue(corePass.contains("constexpr std::uint32_t kFrameFlagCameraInWater = 1u << 5u;"),
            "the core names the shader bit it writes, so the contract is readable at the mapping");
        assertTrue(corePass.contains("| ((desc.flags & BEDRAY_PATH_TRACE_FRAME_CAMERA_IN_WATER) != 0u ? kFrameFlagCameraInWater : 0u);"),
            "the host bit becomes the shader bit in the frame word the path tracer reads");
        assertTrue(corePass.contains("| BEDRAY_PATH_TRACE_FRAME_CAMERA_IN_WATER;"),
            "an unlisted flag is rejected by the record validation, so the new bit has to be admitted");

        String fog = read(JAVA + "/RtFogState.java");
        String collect = block(fog, "void collect(Minecraft mc, float partialTick,");
        assertTrue(collect.contains("medium = 0;"),
            "every frame starts the verdict on air, so swimming out of the body clears it");
        assertTrue(collect.contains("camera.getFluidInCamera()"), "the engine, not the geometry, says which fluid the eye is in");
        assertTrue(collect.indexOf("type == FogType.WATER") < collect.indexOf("medium = 1;"),
            "only the water branch may claim the medium");
        assertEquals(2, count(collect, "medium = "),
            "air default plus water: a third value would need its own ABI meaning");
    }

    private static int count(String source, String needle) {
        int found = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
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
