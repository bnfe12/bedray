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
        assertTrue(frameData.contains("frame.flags = (p.cameraInWater ? RtPathTraceSlangPass.Frame.FLAG_CAMERA_IN_WATER : 0)"),
            "one conversion for every native pass, or the two path entries could disagree");
        assertFalse(frameData.contains("frame.flags = 0;"), "the flags word has no unconditional zero left to overwrite it");

        String renderer = read(JAVA + "/RtFrameRenderer.java");
        assertTrue(renderer.contains("params.cameraInWater = fogState.medium == 1;"),
            "the verdict comes from the same camera fluid state that already picks the water fog");

        // Two backends, two producers of the frame word: the Slang pass writes it itself, while the native core builds
        // its own FrameConstants from a host bit, so the medium has to reach both or a playtest of the live path shows
        // no water at all.
        String pathPass = read(JAVA + "/RtSlangPathPass.java");
        assertTrue(pathPass.contains("| (params.cameraInWater ? BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_IN_WATER : 0)"),
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
        String mask = featureMask(coreSource);
        assertTrue(mask.contains("BEDRAY_PATH_TRACE_FEATURE_CAMERA_IN_WATER")
                && mask.contains("BEDRAY_PATH_TRACE_FEATURE_WATER_MODEL"),
            "the feature mask has to report what the record validation already accepts");

        String corePass = read("../native/bedray_core/src/path_trace_pass.cpp");
        assertTrue(corePass.contains("constexpr std::uint32_t kFrameFlagCameraInWater = 1u << 5u;"),
            "the core names the shader bit it writes, so the contract is readable at the mapping");
        assertTrue(corePass.contains("| ((desc.flags & BEDRAY_PATH_TRACE_FRAME_CAMERA_IN_WATER) != 0u ? kFrameFlagCameraInWater : 0u)"),
            "the host bit becomes the shader bit in the frame word the path tracer reads");
        assertTrue(corePass.contains("| BEDRAY_PATH_TRACE_FRAME_CAMERA_IN_WATER"),
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

    /**
     * The settings screen has always had a water style and a caustics switch, and the debug line has always reported
     * both — but the typed path read neither, so {@code water=vanilla} and {@code caustics=off} changed a label and
     * nothing else.  The two verdicts now travel the same two-backend route as the eye's own medium: one params bit
     * from the settings, a frame word for the Slang backend, a host bit and a mapping for the native core, and one
     * feature bit so a host never sets bits an older DLL would reject outright.
     *
     * What each verdict is allowed to change is the owner's rule, not an implementation detail: <b>style is the wave
     * field</b> — the same ripples at full tilt or damped toward the flat sheet, and nothing else, so an underwater
     * floor is lit by the same bent beam in either setting — while <b>caustics is the focusing</b> of that beam by
     * whatever waves the style left.  A gate that made the style pick a different medium model would have been the
     * easy version and wrong water.
     */
    @Test
    void thePlayerSwitchesReachTheFrameWord() {
        String contract = read(SHADERS + "/contracts/frame.slang");
        int realistic = flagBit(contract, "FRAME_FLAG_WATER_REALISTIC");
        int caustics = flagBit(contract, "FRAME_FLAG_WATER_CAUSTICS");
        assertEquals(6, realistic, "bits 0-5 are the history, cut, reset, first-person, debug and camera-medium flags");
        assertEquals(7, caustics, "one bit per switch, so a frame can carry either alone");
        assertEquals(1 << realistic, RtPathTraceSlangPass.Frame.FLAG_WATER_REALISTIC,
            "the Java mirror drifted from the shader's bit");
        assertEquals(1 << caustics, RtPathTraceSlangPass.Frame.FLAG_WATER_CAUSTICS,
            "the Java mirror drifted from the shader's bit");
        assertTrue(contract.contains("bool frameWaterRealisticWaves(FrameConstants frame) {")
                && contract.contains("bool frameWaterCaustics(FrameConstants frame) {"),
            "the pass reads the switches through helpers, so a renamed bit cannot be missed");
        assertFalse(contract.contains("frameWaterVolumeStyle"),
            "the style predicate is named for the waves it scales: a name that claims a volume model invites a "
                + "gate on the light");

        String entry = read(SHADERS + "/pathtrace/path_trace_entry.slang");
        assertTrue(entry.contains("materialOptions.waterWaveMix = frameWaterRealisticWaves(f) ? 1.0 "
                + ": WATER_WAVE_MIX_VANILLA;"),
            "the style verdict reaches the wave field, which is the only thing the switch owns");
        assertTrue(entry.contains("options.waterCaustics = frameWaterCaustics(f);"),
            "and the same for the focus");
        assertFalse(entry.contains("PATH_TRACE_OPTION_WATER"),
            "the switches are not push-constant bits: that block mirrors a host ABI word for word and is not the "
                + "shader's own decision space");
        assertFalse(entry.contains("waterVolumeStyle"),
            "there is no volume-style gate left to set");

        String tracer = read(TRACER);
        assertFalse(tracer.contains("waterVolumeStyle"),
            "the medium never reads the style switch: absorption, IOR and the bent beam are the same water in both "
                + "styles, and a name for a gate is how the gate comes back");
        String term = block(tracer, "float3 pathTraceDirectTerm<T : ISceneTracer>");
        assertTrue(term.contains("if (medium.inside && (light.kind == LightKind.Sun "
                + "|| light.kind == LightKind.Moon))"),
            "the beam is asked for under every style: the owner's rule is parity of the light and a difference in "
                + "the waves");
        assertTrue(term.contains("float beamWeight = beam.valid") && term.contains("(options.waterCaustics"),
            "the focus is consulted only on a beam that exists, so caustics-off cannot gather light the switch never "
                + "let through");
        assertTrue(term.contains("waterBeamFocus(beam, position, lightDirection, materialOptions.portalTime,\n"
                + "                materialOptions.waterWaveMix) : 1.0)"),
            "the caustic bends through the same scaled face the receiver's own normal uses, so damped waves dance "
                + "gently instead of not at all");
        assertFalse(term.contains("options.flags & PATH_TRACE_OPTION_WATER"),
            "the direct term asks the options struct, never the mirrored push flags");
        String defaults = block(tracer, "PathTraceOptions defaultPathTraceOptions() {");
        assertTrue(defaults.contains("options.waterCaustics = false;"),
            "a frame that says nothing gathers nothing, the same fallback every older build had");

        String water = read(WATER);
        assertTrue(water.contains("static const float WATER_WAVE_MIX_VANILLA = 0.55;"),
            "the damping is the legacy sheet's own mix, so the switch means the same water it always meant");
        String styled = block(water, "float3 waterStyledWaveNormal(");
        assertTrue(styled.contains("waterWaveNormal(worldPosition, geometricNormal, time, footprint, lostVar)")
                && styled.contains("safeNormalize(lerp(geometricNormal, wave, max(waveMix, 0.0)), geometricNormal)"),
            "the style leans the analytic normal toward the geometric one and never recomputes a different field");
        assertTrue(block(water, "float2 waterBeamAtDepth(").contains("waterStyledWaveNormal("),
            "the beam's entry face is the styled one: the only wave-aware step of the light path");
        assertFalse(block(water, "WaterBeam waterBeamToLight(").contains("waterStyledWaveNormal")
                || block(water, "WaterBeam waterBeamToLight(").contains("waveMix"),
            "the column itself is the plane model: the style cannot move where the light enters or how deep it goes");

        String fluid = block(read(SURFACE), "&& waterIsSurface(true, material.f0");
        assertTrue(fluid.contains("waterStyledWaveNormal(hit.worldPosition, geometricNormal,\n"
                + "                    options.portalTime, footprint, options.waterWaveMix, lostVar)"),
            "the decoded face keeps the field the switch scales");
        assertTrue(fluid.indexOf("material.ior = WATER_IOR;") < fluid.indexOf("options.waterWaveMix >= 1.0"),
            "the optical profile is set before the style is ever consulted, so no style can pick a different water");
        assertTrue(fluid.contains("clamp(sqrt(max(lostVar, 0.0)) * 1.6, 0.02, 0.30)")
                && fluid.contains("clamp(0.02 + sqrt(max(lostVar, 0.0)) * 0.8, 0.02, 0.16)"),
            "the two sheens are the legacy pair: the full field roughens more, because roughness is where the "
                + "filtered slopes went");

        // Producer: one conversion from the settings to the params bits, shared by both backends.
        String renderer = read(JAVA + "/RtFrameRenderer.java");
        assertTrue(renderer.contains("RtSettings.waterStyle() == 1 ? RtFrameParams.FLAG_WATER_REALISTIC : 0"),
            "the style switch is read once, for every pass");
        assertTrue(renderer.contains("RtSettings.caustics() ? RtFrameParams.FLAG_CAUSTICS : 0"),
            "and so is the caustics switch");

        String frameData = read(JAVA + "/RtSlangFrameData.java");
        assertTrue(frameData.contains("(p.flags & RtFrameParams.FLAG_WATER_REALISTIC) != 0 "
                + "? RtPathTraceSlangPass.Frame.FLAG_WATER_REALISTIC : 0)"),
            "the Slang backend writes its own frame word");
        assertTrue(frameData.contains("(p.flags & RtFrameParams.FLAG_CAUSTICS) != 0 "
                + "? RtPathTraceSlangPass.Frame.FLAG_WATER_CAUSTICS : 0)"),
            "the focus switch goes into the same word, from the same params bit");

        String pathPass = read(JAVA + "/RtSlangPathPass.java");
        assertTrue(pathPass.contains("? BedrayCoreLayouts.PATH_TRACE_FRAME_WATER_REALISTIC : 0)"),
            "the native backend gets the same two verdicts as host bits");
        assertTrue(pathPass.contains("? BedrayCoreLayouts.PATH_TRACE_FRAME_WATER_CAUSTICS : 0)"));

        String layouts = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreLayouts.java");
        String coreHeader = read("../native/bedray_core/include/bedray_core.h");
        int realisticHost = hostBit(layouts, "PATH_TRACE_FRAME_WATER_REALISTIC");
        int causticsHost = hostBit(layouts, "PATH_TRACE_FRAME_WATER_CAUSTICS");
        assertEquals(1 << realisticHost, abiBit(coreHeader, "BEDRAY_PATH_TRACE_FRAME_WATER_REALISTIC"),
            "the Java host bit and the core's declaration must be the same number");
        assertEquals(1 << causticsHost, abiBit(coreHeader, "BEDRAY_PATH_TRACE_FRAME_WATER_CAUSTICS"),
            "the Java host bit and the core's declaration must be the same number");
        assertEquals(hostBit(layouts, "PATH_TRACE_FRAME_CAMERA_IN_WATER") + 1,
            realisticHost, "the host word stays a single ascending list");
        assertEquals(realisticHost + 1, causticsHost, "and the pair is adjacent, so one mask can drop both");
        assertEquals(realistic + 1, caustics, "the two shader bits are neighbours, as the contract documents");
        assertTrue(layouts.contains("public static final int PATH_TRACE_FEATURE_WATER_MODEL = 1 << 5;"),
            "one advertised feature for the pair: a core that maps half a switch pair is worse than one that maps "
                + "neither");
        assertEquals(abiBit(layouts, "PATH_TRACE_FEATURE_WATER_MODEL"),
            abiBit(coreHeader, "BEDRAY_PATH_TRACE_FEATURE_WATER_MODEL"),
            "the host probes the mask bit the core exports, or the pair is masked away on every frame");

        String nativeTrace = read(JAVA + "/BedrayPathTraceNative.java");
        assertTrue(nativeTrace.contains("if (!library.hasWaterModelFlags()) {"),
            "an older core answers an unknown flag with a rejected frame: the verdicts are dropped, not negotiated");
        assertTrue(nativeTrace.contains("flags &= ~(BedrayCoreLayouts.PATH_TRACE_FRAME_WATER_REALISTIC"),
            "and both go together, so the frame cannot claim a style the core would not map");

        String coreNative = read("src/main/java/io/github/andrexarlay/vulkancompat/rt/core/BedrayCoreNative.java");
        assertTrue(coreNative.contains("hasPathTraceFeature(BedrayCoreLayouts.PATH_TRACE_FEATURE_WATER_MODEL)"),
            "the probe asks the mask the core actually exports");

        String coreSource = read("../native/bedray_core/src/core.cpp");
        assertTrue(featureMask(coreSource).contains("BEDRAY_PATH_TRACE_FEATURE_WATER_MODEL"),
            "the feature mask has to report what the record validation accepts");

        String corePass = read("../native/bedray_core/src/path_trace_pass.cpp");
        assertEquals(1 << realistic, abiBit(corePass, "kFrameFlagWaterRealistic"),
            "the core names the shader bit it writes");
        assertEquals(1 << caustics, abiBit(corePass, "kFrameFlagWaterCaustics"));
        assertTrue(corePass.contains("? kFrameFlagWaterRealistic : 0u)")
                && corePass.contains("? kFrameFlagWaterCaustics : 0u)"),
            "each host bit becomes its shader bit in the frame word the path tracer reads");
        assertTrue(corePass.contains("| BEDRAY_PATH_TRACE_FRAME_WATER_REALISTIC | BEDRAY_PATH_TRACE_FRAME_WATER_CAUSTICS;"),
            "an unlisted flag is rejected by the record validation, so both have to be admitted");
    }

    /** The body of {@code bedray_core_path_trace_feature_mask}, read as one block so a new bit cannot slip past. */
    private static String featureMask(String coreSource) {
        int start = coreSource.indexOf("bedray_core_path_trace_feature_mask");
        assertTrue(start >= 0, "the core exports no path-trace feature mask");
        int end = coreSource.indexOf("} catch", start);
        assertTrue(end > start, "the feature mask is no longer a single return of advertised bits");
        return coreSource.substring(start, end);
    }

    /** {@code NAME = 1u << N} in the Slang contract, as a bit index. */
    private static int flagBit(String source, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*1u\\s*<<\\s*(\\d+)u").matcher(source);
        assertTrue(m.find(), name + " must be declared as a single bit in the contract");
        return Integer.parseInt(m.group(1));
    }

    /** A {@code 1 << N} Java constant or a {@code (1u << N)} C define / constexpr, as a value. */
    private static int abiBit(String source, String name) {
        Matcher m = Pattern.compile(name + "\\s*=?\\s*\\(?1u?\\s*<<\\s*(\\d+)\\)?").matcher(source);
        assertTrue(m.find(), name + " is not declared as a single bit");
        return 1 << Integer.parseInt(m.group(1));
    }

    /** The same declaration, as the bit index, for the host word. */
    private static int hostBit(String source, String name) {
        Matcher m = Pattern.compile(name + "\\s*=\\s*1\\s*<<\\s*(\\d+)").matcher(source);
        assertTrue(m.find(), name + " is not declared as a single bit");
        return Integer.parseInt(m.group(1));
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
