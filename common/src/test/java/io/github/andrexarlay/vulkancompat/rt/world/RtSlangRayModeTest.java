package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rtx.config.RtSettings;
import org.junit.jupiter.api.Test;
import org.lwjgl.vulkan.VkDevice;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ray Tracing mode runs the ray profile of the same Slang pass Path Tracing runs: one indirect bounce, one sample,
 * no adaptive governor, its own {@code rays_*} switches. The deleted {@code rt_rays.comp} drew the same terms as
 * separate rays; this profile gets them from the path transport, so there is one shader to keep correct.
 */
class RtSlangRayModeTest {
    private static final Path RENDERER = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtFrameRenderer.java");
    private static final Path PASS = Path.of("src/main/java/io/github/andrexarlay/vulkancompat/rt/world/"
        + "RtSlangPathPass.java");

    /** Records what the host hands the backend, without Vulkan objects or a driver. */
    private static final class Backend implements PathTraceBackend {
        PathTraceBackend.Frame frame;
        public void prepare(int width, int height) {}
        public void record(PathTraceBackend.Frame value) { frame = value; }
        public boolean spatialAvailable() { return true; }
        public String reservoirState() { return "native"; }
        public void releaseRecording(long command) {}
        public boolean discardRecording(long command) { return false; }
        public void releaseRecordingsAfterHostDrain() {}
        public void close() {}
    }

    private static RtSlangPathPass pass(Backend backend, boolean rayMode) {
        RtSlangPathPass pass = new RtSlangPathPass(backend, false, rayMode);
        pass.bind(new RtFrameBindings.Views(1, null, 2, 3, 4, 5, 0, null, 6));
        pass.prepare(null, null, 16, 8);
        return pass;
    }

    /** The whole point of the profile: the push says one indirect bounce and one sample, whatever the path preset is. */
    @Test
    void theRayProfileTracesOneIndirectBounceAndOneSample() {
        int savedSpp = RtSettings.pathSpp;
        int savedBounces = RtSettings.pathBounces;
        try {
            RtSettings.pathSpp = 8;
            RtSettings.pathBounces = 5;
            Backend backend = new Backend();
            RtSlangPathPass rays = pass(backend, true);
            RtFrameParams params = new RtFrameParams();
            params.rtRadiusXz = 12;
            rays.configure(params, false);
            assertEquals(RtSlangPathPass.RAY_MODE_MAX_DEPTH, params.bounces,
                "the ray profile owns its depth, not the path preset");
            assertEquals(RtSlangPathPass.RAY_MODE_SPP, params.spp,
                "one sample: a frame is one sample and the mean carries the rest");
            assertEquals(12 * 16, params.rayDistance,
                "the ray profile traces no farther than the world the scene was built for (RT Distance)");
            rays.dispatch(null, params, 16, 8);
            assertEquals(RtSlangPathPass.RAY_MODE_MAX_DEPTH, backend.frame.dispatch().maxDepth(),
                "the bounce count reaches the shader, not only the describe line");
            assertEquals(2, RtSlangPathPass.RAY_MODE_MAX_DEPTH,
                "two scattering events are the primary surface plus one indirect one");
            assertTrue(rays.describe(params).startsWith("ray_slang("), "the mode log names the ray profile");
        } finally {
            RtSettings.pathSpp = savedSpp;
            RtSettings.pathBounces = savedBounces;
        }
    }

    /** A ray pass reports the ray mode, so the renderer's rebuild check and the logs see the right pass. */
    @Test
    void eachProfileReportsItsOwnMode() {
        assertEquals(RtMode.RAY_TRACED, new RtSlangPathPass(new Backend(), false, true).mode());
        assertEquals(RtMode.PATH_TRACED, new RtSlangPathPass(new Backend(), false, false).mode());
    }

    /** The path profile keeps the player's governor: this change must not have pinned full path tracing. */
    @Test
    void thePathProfileKeepsTheGovernor() {
        Backend backend = new Backend();
        RtSlangPathPass path = pass(backend, false);
        RtFrameParams params = new RtFrameParams();
        path.configure(params, false);
        assertEquals(RtAdaptiveQuality.bounces(RtSettings.spp(), RtSettings.bounces()), params.bounces);
        assertEquals(RtAdaptiveQuality.spp(RtSettings.spp()), params.spp);
        assertTrue(path.describe(params).startsWith("path_slang("));
    }

    /** Each profile reads its own reuse switches; neither may leave the other's rows dead. */
    @Test
    void eachProfileReadsItsOwnReuseSwitches() {
        boolean savedTemporal = RtSettings.raysRestirTemporal;
        boolean savedPathDi = RtSettings.pathRestirDi;
        try {
            RtSettings.raysRestirTemporal = false;
            RtSettings.pathRestirDi = true;
            Backend rayBackend = new Backend();
            RtSlangPathPass rays = pass(rayBackend, true);
            RtFrameParams params = new RtFrameParams();
            params.emitterCount = 1;
            params.emitterHandle = 1L;
            rays.configure(params, false);
            rays.dispatch(null, params, 16, 8);
            assertEquals(0, rayBackend.frame.dispatch().diFlags(),
                "rays_restir_temporal off means the ray profile hands the shader no reuse");

            RtSettings.raysRestirTemporal = true;
            RtSettings.pathRestirDi = false;
            Backend rayOn = new Backend();
            RtSlangPathPass raysOn = pass(rayOn, true);
            raysOn.configure(params, false);
            raysOn.dispatch(null, params, 16, 8);
            assertTrue(rayOn.frame.dispatch().diFlags() != 0,
                "the ray profile follows rays_restir_temporal, not path_restir_di");

            Backend pathBackend = new Backend();
            RtSlangPathPass path = pass(pathBackend, false);
            path.configure(params, false);
            path.dispatch(null, params, 16, 8);
            assertEquals(0, pathBackend.frame.dispatch().diFlags(),
                "the path profile follows path_restir_di, not rays_restir_temporal");
        } finally {
            RtSettings.raysRestirTemporal = savedTemporal;
            RtSettings.pathRestirDi = savedPathDi;
        }
    }

    /**
     * The effect switches of the ray mode reach the shader as option bits: every switch the screen offers must change
     * what the one path traces, or it is a dead control.
     */
    @Test
    void everyEffectSwitchBecomesAnOptionBit() {
        boolean sShadows = RtSettings.raysShadows;
        boolean sSoft = RtSettings.raysSoftShadows;
        boolean sRefl = RtSettings.raysReflections;
        boolean sAo = RtSettings.raysAo;
        boolean sGi = RtSettings.raysGi;
        try {
            RtSettings.raysShadows = true;
            RtSettings.raysSoftShadows = true;
            RtSettings.raysReflections = true;
            RtSettings.raysAo = true;
            RtSettings.raysGi = true;
            RtSlangPathPass rays = pass(new Backend(), true);
            assertEquals(0, rays.rayEffectFlags(), "everything on is the full transport, no bits");

            RtSettings.raysReflections = false;
            assertEquals(RtPathTraceSlangPass.OPTION_NO_SPECULAR_BOUNCE, rays.rayEffectFlags(),
                "reflections off ends a specular continuation");

            RtSettings.raysGi = false;
            RtSettings.raysAo = true;
            assertEquals(RtPathTraceSlangPass.OPTION_NO_SPECULAR_BOUNCE
                    | RtPathTraceSlangPass.OPTION_AMBIENT_ONLY_BOUNCE,
                rays.rayEffectFlags(), "no GI with AO keeps the diffuse bounce for the sky alone");

            RtSettings.raysAo = false;
            assertEquals(RtPathTraceSlangPass.OPTION_NO_SPECULAR_BOUNCE
                    | RtPathTraceSlangPass.OPTION_NO_DIFFUSE_BOUNCE,
                rays.rayEffectFlags(), "neither GI nor AO ends the diffuse continuation");

            RtSettings.raysShadows = false;
            RtSettings.raysSoftShadows = false;
            assertEquals(RtPathTraceSlangPass.OPTION_NO_SPECULAR_BOUNCE
                    | RtPathTraceSlangPass.OPTION_NO_DIFFUSE_BOUNCE
                    | RtPathTraceSlangPass.OPTION_NO_KEY_SHADOWS
                    | RtPathTraceSlangPass.OPTION_HARD_KEY_SHADOWS,
                rays.rayEffectFlags(), "shadows off skips the walk; soft shadows off draws the disc axis");

            RtSlangPathPass path = pass(new Backend(), false);
            assertEquals(0, path.rayEffectFlags(), "the path profile has no per-effect switches and sets no bits");
        } finally {
            RtSettings.raysShadows = sShadows;
            RtSettings.raysSoftShadows = sSoft;
            RtSettings.raysReflections = sRefl;
            RtSettings.raysAo = sAo;
            RtSettings.raysGi = sGi;
        }
    }

    /** The distance sliders and the block-light switches travel in the push words the shader reads them from. */
    @Test
    void theDistanceSlidersAndBlockLightSwitchReachThePush() {
        int sGiDistance = RtSettings.raysGiDistance;
        int sReflDistance = RtSettings.raysReflectionDistance;
        int sAoRadius = RtSettings.raysAoRadius;
        int sEmissiveDistance = RtSettings.raysEmissiveDistance;
        int sEmissiveQuality = RtSettings.raysEmissiveQuality;
        boolean sEmissive = RtSettings.raysEmissive;
        boolean sGi = RtSettings.raysGi;
        boolean sAo = RtSettings.raysAo;
        try {
            RtSettings.raysGi = true;
            RtSettings.raysAo = true;
            RtSettings.raysGiDistance = 200;
            RtSettings.raysReflectionDistance = 300;
            RtSettings.raysEmissiveDistance = 40;
            RtSettings.raysEmissiveQuality = 2;
            RtSettings.raysEmissive = true;
            Backend backend = new Backend();
            RtSlangPathPass rays = pass(backend, true);
            RtFrameParams params = new RtFrameParams();
            params.emitterCount = 3;
            params.emitterHandle = 1L;
            rays.configure(params, false);
            rays.dispatch(null, params, 16, 8);
            var dispatch = backend.frame.dispatch();
            assertEquals(3, dispatch.emitterCount(), "RT Block Light on keeps the emitter list");
            assertEquals(16, dispatch.emitterCandidates(), "two quality steps are sixteen RIS candidates");
            assertEquals(48f, dispatch.emitterDistance(), "the emitter sampler stops where the list was collected");
            assertEquals(200f, dispatch.diffuseDistance(), "GI distance is the diffuse bounce far plane");
            assertEquals(300f, dispatch.specularDistance(), "reflection distance is the specular far plane");

            RtSettings.raysEmissive = false;
            Backend off = new Backend();
            RtSlangPathPass noBlockLight = pass(off, true);
            RtFrameParams offParams = new RtFrameParams();
            offParams.emitterCount = 3;
            offParams.emitterHandle = 1L;
            noBlockLight.configure(offParams, false);
            noBlockLight.dispatch(null, offParams, 16, 8);
            assertEquals(0, off.frame.dispatch().emitterCount(),
                "RT Block Light off drops the list from the trace entirely");

            RtSettings.raysGi = false;
            RtSettings.raysAo = true;
            RtSettings.raysAoRadius = 5;
            Backend ao = new Backend();
            RtSlangPathPass aoOnly = pass(ao, true);
            RtFrameParams aoParams = new RtFrameParams();
            aoOnly.configure(aoParams, false);
            aoOnly.dispatch(null, aoParams, 16, 8);
            assertEquals(5f, ao.frame.dispatch().diffuseDistance(),
                "the AO radius is the far plane of the ambient-only bounce");
        } finally {
            RtSettings.raysGiDistance = sGiDistance;
            RtSettings.raysReflectionDistance = sReflDistance;
            RtSettings.raysAoRadius = sAoRadius;
            RtSettings.raysEmissiveDistance = sEmissiveDistance;
            RtSettings.raysEmissiveQuality = sEmissiveQuality;
            RtSettings.raysEmissive = sEmissive;
            RtSettings.raysGi = sGi;
            RtSettings.raysAo = sAo;
        }
    }

    /** AO and DIRECT are debug shadings of the deleted pass: the renderer names them instead of showing a lit image. */
    @Test
    void aoAndDirectAreRefusedByName() throws Exception {
        Method create = RtFrameRenderer.class.getDeclaredMethod("createPass", VkDevice.class, RtMode.class, boolean.class);
        create.setAccessible(true);
        for (RtMode mode : new RtMode[]{RtMode.AO, RtMode.DIRECT}) {
            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> create.invoke(null, null, mode, false));
            RtFailure failure = assertInstanceOf(RtFailure.class, thrown.getCause());
            assertEquals("slang_" + mode.logName(), failure.stage(), mode + " refuses under its own name");
        }
    }

    /** The host ring the deleted ray pass owned must not come back: the backend binds its own reservoirs. */
    @Test
    void noHostReservoirRingSurvivesTheRayPass() throws IOException {
        String renderer = read(RENDERER);
        assertFalse(renderer.contains("rt_restir"), "the host allocation of the deleted ray pass is gone");
        assertFalse(renderer.contains("RESERVOIR_BYTES"), "and so are its size constants");
        assertTrue(renderer.contains("MemoryUtil.memPutLong(hdr + RtStatsLayout.RESERVOIR_ADDR, 0L);"),
            "the record keeps the two slots at zero for the layout contract");
        assertTrue(renderer.contains("createPass(device, passModeFor(RtConfig.mode()), wantGbuffer(passModeFor(RtConfig.mode())));"),
            "the ray mode now builds a pass instead of failing the session gate");
        String pass = read(PASS);
        assertTrue(pass.contains(
                "return diRequested() && (rayMode ? RtSettings.restirTemporal() : RtSettings.pathRestirDi());"),
            "the ray profile's reuse switch is wired where the frame is recorded");
        assertTrue(pass.contains("rayMode ? RtSettings.raysAccumulation() : RtSettings.accumulate();"),
            "and so is its accumulate switch");
    }

    /**
     * The host bits are only half the contract: the shader has to define the same values and act on them, or a switch
     * reaches a flag word nothing reads.
     */
    @Test
    void theShaderDefinesAndHonoursTheSameEffectBits() throws IOException {
        String tracer = read(Path.of("src/main/resources/shaders/slang/pathtrace/path_tracer.slang"));
        assertTrue(tracer.contains("PATH_TRACE_OPTION_NO_SPECULAR_BOUNCE  = 1u << 2u;"), "specular bit");
        assertTrue(tracer.contains("PATH_TRACE_OPTION_NO_DIFFUSE_BOUNCE   = 1u << 3u;"), "diffuse bit");
        assertTrue(tracer.contains("PATH_TRACE_OPTION_AMBIENT_ONLY_BOUNCE = 1u << 4u;"), "ambient-only bit");
        assertTrue(tracer.contains("PATH_TRACE_OPTION_NO_KEY_SHADOWS      = 1u << 5u;"), "unshadowed key bit");
        assertTrue(tracer.contains("PATH_TRACE_OPTION_HARD_KEY_SHADOWS    = 1u << 6u;"), "hard-shadow bit");
        assertTrue(tracer.contains("continuationAmbientOnly = (options.flags & PATH_TRACE_OPTION_AMBIENT_ONLY_BOUNCE) != 0u;"),
            "the diffuse continuation reads the AO bit");
        assertTrue(tracer.contains("if (ambientOnlyPending) {"),
            "an ambient-only bounce that finds geometry stops the path");
        assertTrue(tracer.contains("if ((options.flags & PATH_TRACE_OPTION_NO_KEY_SHADOWS) != 0u"),
            "the key light skips its visibility walk when the switch is off");
        assertTrue(tracer.contains("ray = makeRay(origin, bsdf.direction, 0.0, continuationDistance);"),
            "the per-lobe far plane is the one the bounce uses");

        String sun = read(Path.of("src/main/resources/shaders/slang/light/sun.slang"));
        assertTrue(sun.contains("float3 direction = hardShadows ? axis : coneAround(axis, radius, drawSeed);"),
            "hard shadows draw the disc axis but keep the disc density; the disc point comes from the receiver's "
                + "world-locked seed when it has one, or the path's own");

        String entry = read(Path.of("src/main/resources/shaders/slang/pathtrace/path_trace_entry.slang"));
        assertTrue(entry.contains("keyLight.hardShadows = (pc.flags & PATH_TRACE_OPTION_HARD_KEY_SHADOWS) != 0u;"),
            "the entry pass sets the key-light mode from the host bit");
        assertTrue(entry.contains("options.specularDistance = asfloat(pc.diState.w) > 0.0 ? asfloat(pc.diState.w) : options.maxDistance;"),
            "the specular far plane comes from the push word and falls back to the ray far plane");
    }

    private static String read(Path relativeToCommon) throws IOException {
        return Files.readString(relativeToCommon).replace("\r\n", "\n");
    }
}
