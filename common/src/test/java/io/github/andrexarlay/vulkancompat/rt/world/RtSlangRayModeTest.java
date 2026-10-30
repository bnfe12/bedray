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
            rays.configure(params, false);
            assertEquals(RtSlangPathPass.RAY_MODE_MAX_DEPTH, params.bounces,
                "the ray profile owns its depth, not the path preset");
            assertEquals(RtSlangPathPass.RAY_MODE_SPP, params.spp,
                "one sample: a frame is one sample and the mean carries the rest");
            assertEquals(RtSettings.pathMaxDistance(), params.rayDistance,
                "the one distance slider this tracer has bounds the secondary segments");
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

    private static String read(Path relativeToCommon) throws IOException {
        return Files.readString(relativeToCommon).replace("\r\n", "\n");
    }
}
