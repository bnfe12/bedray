package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.core.BedraySpatialNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Built-in spatial upscalers (no vendor library, any Vulkan GPU): AMD FidelityFX FSR 1 (EASU, then RCAS when the sharpness is
 * above 0) and NVIDIA Image Scaling (NVScaler, which includes its own sharpening). Both read the scaled world colour (render
 * size) and write the display-size output image; the caller copies that into the main target.
 * <p>
 * The render scale comes from {@link UpscalerRequest#current()}: the FSR 1 mode ratio (GPUOpen table, UpscalerModes) or the
 * custom percent. AOT programs and samplers belong to a shared runtime lease; native owns immutable descriptors and barriers.
 * A failure is logged once as {@code [VKB-SPATIAL] spatial=FAILED stage=.. backend=..} and the frame falls back to the
 * bilinear blit; the backend stays disabled until the request changes (no hidden retry). Render thread only.
 */
public final class SpatialUpscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/SpatialUpscaler");

    private static SlangRuntimePool.Lease runtimeLease;
    private static BedraySpatialNative recorder;
    private static final Set<Long> recordings = new LinkedHashSet<>();
    private static SpatialComputePass easu;
    private static SpatialComputePass rcas;
    private static SpatialComputePass nis;
    private static UpscalerRequest.Request failedFor;
    private static long dispatches;
    private static String lastLogged;

    private SpatialUpscaler() {}

    /** The selected backend when it is FSR1 or NIS and has not failed for this request, else null. */
    public static UpscalerBackend selected() {
        UpscalerRequest.Request r = UpscalerRequest.current();
        if (!r.backend().isSpatial() || r.equals(failedFor)) {
            return null;
        }
        return r.backend();
    }

    /** Number of recorded spatial dispatches since startup (autotest evidence that the path ran). */
    public static long dispatches() {
        return dispatches;
    }

    public static boolean isSelected() {
        return selected() != null;
    }

    /** Render scale (render / output, per axis) of the selected spatial backend; 1.0 when none is selected. */
    public static double renderScale() {
        UpscalerRequest.Request r = UpscalerRequest.current();
        if (!r.backend().isSpatial()) {
            return 1.0;
        }
        if (r.spatialMode() == UpscalerMode.CUSTOM) {
            return r.customPercent() / 100.0;
        }
        return UpscalerModes.scale(r.backend(), r.spatialMode());
    }

    /** Render size for an output size: rounded, at least 1, never above the output. */
    public static int renderSize(int output) {
        return Math.max(1, Math.min(output, (int) Math.round(output * renderScale())));
    }

    /**
     * Records the selected spatial upscaler into {@code cmd} (barriers included).
     *
     * @param inView   colour view of the world target (render size), sampled
     * @param tmpView  display-size RGBA8 storage view used between EASU and RCAS (ignored by NIS / RCAS off)
     * @param outView  display-size RGBA8 storage view that receives the result
     * @return true when the output was produced
     */
    public static boolean record(VkDevice vkDevice, VkCommandBuffer cmd, long inView, int inW, int inH,
                                 long tmpView, long outView, int outW, int outH) {
        UpscalerBackend backend = selected();
        if (backend == null) {
            return false;
        }
        UpscalerRequest.Request request = UpscalerRequest.current();
        String stage = "init";
        try {
            ensure(vkDevice, backend);
            stage = "record";
            boolean sharpen = backend == UpscalerBackend.FSR1 && VulkanRtxConfig.fsr1Sharpness > 0f;
            ByteBuffer primary, secondary = null;
            if (backend == UpscalerBackend.FSR1) {
                primary = ints(Fsr1Constants.easu(inW, inH, outW, outH), outW, outH);
                if (sharpen) secondary = ints(Fsr1Constants.rcas(Fsr1Constants.stopsForSlider(VulkanRtxConfig.fsr1Sharpness)), outW, outH);
            } else {
                primary = NisConstants.scaler(VulkanRtxConfig.nisSharpness, inW, inH, outW, outH);
            }
            long[] programs = {easu == null ? 0 : easu.nativeHandle(), rcas == null ? 0 : rcas.nativeHandle(), nis == null ? 0 : nis.nativeHandle()};
            var runtime = runtimeLease.runtime();
            recordings.add(cmd.address());
            recorder.record(cmd.address(), programs, runtime.linearSampler(), runtime.atlasSampler(), inView, tmpView, outView,
                inW, inH, outW, outH, backend == UpscalerBackend.FSR1 ? BedraySpatialNative.FSR1 : BedraySpatialNative.NIS,
                sharpen ? BedraySpatialNative.SHARPEN : 0, primary, secondary);
            log(backend, request, inW, inH, outW, outH, sharpen || backend == UpscalerBackend.NIS);
            return true;
        } catch (RuntimeException | Error e) {
            failedFor = request;
            LOGGER.error("[VKB-SPATIAL] [VKB-AUTOTEST] spatial=FAILED stage={} backend={} request={} input={}x{} output={}x{} result={} (bilinear until the request changes)",
                stage, backend, request, inW, inH, outW, outH, e.toString(), e);
            // A failed chain can contain an earlier EASU dispatch. The host
            // must abandon the complete local CB before choosing bilinear.
            throw e;
        }
    }

    private static void log(UpscalerBackend backend, UpscalerRequest.Request request, int inW, int inH, int outW, int outH, boolean sharpen) {
        dispatches++;
        String key = backend + " " + request + " " + inW + "x" + inH + " " + outW + "x" + outH + " " + sharpen;
        if (!key.equals(lastLogged) || dispatches % 600 == 0) {
            lastLogged = key;
            float sharp = backend == UpscalerBackend.FSR1 ? VulkanRtxConfig.fsr1Sharpness : VulkanRtxConfig.nisSharpness;
            LOGGER.info(String.format(Locale.ROOT,
                "[VKB-SPATIAL] [VKB-AUTOTEST] %s=dispatch ok frames=%d input=%dx%d output=%dx%d scale=%.3f mode=%s sharpness=%.2f%s",
                backend.name().toLowerCase(Locale.ROOT), dispatches, inW, inH, outW, outH, inW / (double) outW,
                request.spatialMode() == UpscalerMode.CUSTOM ? "CUSTOM(" + request.customPercent() + "%)" : request.spatialMode(),
                sharp, backend == UpscalerBackend.FSR1 ? (sharpen ? " passes=easu+rcas" : " passes=easu") : " passes=nvscaler"));
        }
    }

    private static ByteBuffer ints(int[] con, int w, int h) {
        ByteBuffer b = ByteBuffer.allocate(con.length * 4 + 16).order(ByteOrder.nativeOrder());
        for (int c : con) {
            b.putInt(c);
        }
        b.putInt(w).putInt(h).putInt(0).putInt(0);
        return b.flip();
    }

    private static void ensure(VkDevice vkDevice, UpscalerBackend backend) {
        VulkanDevice host = VulkanCapabilityChecker.getVulkanDevice();
        if (host == null || host.vkDevice().address() != vkDevice.address())
            throw new IllegalStateException("spatial upscaler host device is unavailable or changed");
        if (runtimeLease != null && runtimeLease.runtime().hostDevice() != host)
            throw new IllegalStateException("spatial runtime belongs to a different host device");
        if (runtimeLease == null) runtimeLease = SlangRuntimePool.acquire(host);
        var runtime = runtimeLease.runtime();
        if (recorder == null) recorder = new BedraySpatialNative(runtime.library(), runtime.core());
        if (backend == UpscalerBackend.FSR1 && (easu == null || rcas == null)) {
            if (easu == null) easu = new SpatialComputePass(runtime, SpatialComputePass.Stage.EASU);
            if (rcas == null) rcas = new SpatialComputePass(runtime, SpatialComputePass.Stage.RCAS);
            LOGGER.info("[VKB-SPATIAL] fsr1=pipelines_ready source=AOT_FidelityFX_v2.3.0");
        }
        if (backend == UpscalerBackend.NIS && nis == null) {
            nis = new SpatialComputePass(runtime, SpatialComputePass.Stage.NIS);
            LOGGER.info("[VKB-SPATIAL] nis=pipeline_ready source=AOT_NIS_v1.0.3");
        }
    }

    public static void releaseRecording(long command) {
        if (!recordings.contains(command)) return;
        runtimeLease.runtime().releaseRecording(command); recordings.remove(command);
    }
    public static void discardRecording(long command) {
        if (!recordings.contains(command)) return;
        runtimeLease.runtime().discardRecording(command); recordings.remove(command);
    }
    public static boolean hasPendingRecordings() { return !recordings.isEmpty(); }
    /** Called only after pending host encoder work was submitted and its fence completed. */
    public static void releaseRecordingsAfterHostDrain() {
        for (long command : java.util.List.copyOf(recordings)) releaseRecording(command);
    }
    public static VulkanDevice hostDevice() { return runtimeLease == null ? null : runtimeLease.runtime().hostDevice(); }

    /** Closes this feature's lease only after exact proof released its recordings. */
    public static void cleanup() {
        if (!recordings.isEmpty()) throw new IllegalStateException("spatial recordings require completion/discard");
        if (runtimeLease != null) runtimeLease.close(); // Refusal preserves all fields for retry.
        runtimeLease = null; recorder = null; easu = rcas = nis = null;
        failedFor = null; lastLogged = null;
    }
}
