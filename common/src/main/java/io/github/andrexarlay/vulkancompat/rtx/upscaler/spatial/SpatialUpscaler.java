package io.github.andrexarlay.vulkancompat.rtx.upscaler.spatial;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerMode;
import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerModes;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.util.Locale;

/**
 * Built-in spatial upscalers (no vendor library, any Vulkan GPU): AMD FidelityFX FSR 1 (EASU, then RCAS when the sharpness is
 * above 0) and NVIDIA Image Scaling (NVScaler, which includes its own sharpening). Both read the scaled world colour (render
 * size) and write the display-size output image; the caller copies that into the main target.
 * <p>
 * The render scale comes from {@link UpscalerRequest#current()}: the FSR 1 mode ratio (GPUOpen table, UpscalerModes) or the
 * custom percent. Pipelines are compiled on first use (Shaderc, a few ms) and destroyed on teardown after the GPU is idle.
 * A failure is logged once as {@code [VKB-SPATIAL] spatial=FAILED stage=.. backend=..} and the frame falls back to the
 * bilinear blit; the backend stays disabled until the request changes (no hidden retry). Render thread only.
 */
public final class SpatialUpscaler {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/SpatialUpscaler");

    private static VkDevice device;
    private static SpatialComputePass easu;
    private static SpatialComputePass rcas;
    private static SpatialComputePass nis;
    private static long linearSampler;
    private static long nearestSampler;
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
            boolean sharpen;
            VkBarriers.fullMemoryBarrier(cmd);
            if (backend == UpscalerBackend.FSR1) {
                sharpen = VulkanRtxConfig.fsr1Sharpness > 0.0f;
                int[] con = Fsr1Constants.easu(inW, inH, outW, outH);
                ByteBuffer pc = ints(con, outW, outH);
                easu.record(cmd, new long[] {inView, sharpen ? tmpView : outView}, linearSampler, pc,
                    (outW + 7) / 8, (outH + 7) / 8);
                if (sharpen) {
                    VkBarriers.fullMemoryBarrier(cmd);
                    int[] rc = Fsr1Constants.rcas(Fsr1Constants.stopsForSlider(VulkanRtxConfig.fsr1Sharpness));
                    rcas.record(cmd, new long[] {tmpView, outView}, nearestSampler, ints(rc, outW, outH),
                        (outW + 7) / 8, (outH + 7) / 8);
                }
            } else {
                sharpen = true;
                ByteBuffer pc = NisConstants.scaler(VulkanRtxConfig.nisSharpness, inW, inH, outW, outH);
                // NIS block 32x24 per workgroup (nis_scaler.comp NIS_BLOCK_WIDTH/HEIGHT)
                nis.record(cmd, new long[] {0L, inView, outView}, linearSampler, pc, (outW + 31) / 32, (outH + 23) / 24);
            }
            VkBarriers.fullMemoryBarrier(cmd);
            log(backend, request, inW, inH, outW, outH, sharpen);
            return true;
        } catch (RuntimeException | Error e) {
            failedFor = request;
            LOGGER.error("[VKB-SPATIAL] [VKB-AUTOTEST] spatial=FAILED stage={} backend={} request={} input={}x{} output={}x{} result={} (bilinear until the request changes)",
                stage, backend, request, inW, inH, outW, outH, e.toString(), e);
            return false;
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
        if (device != null && device != vkDevice) {
            throw new IllegalStateException("Vulkan device changed under the spatial upscaler");
        }
        device = vkDevice;
        if (linearSampler == 0L) {
            linearSampler = sampler(VK10.VK_FILTER_LINEAR);
            nearestSampler = sampler(VK10.VK_FILTER_NEAREST);
        }
        long t0 = System.nanoTime();
        if (backend == UpscalerBackend.FSR1 && easu == null) {
            easu = new SpatialComputePass(device, "fsr1_easu.comp", SpatialShaderSource.easuSource(),
                new SpatialComputePass.Binding[] {SpatialComputePass.Binding.COMBINED, SpatialComputePass.Binding.STORAGE}, 80);
            rcas = new SpatialComputePass(device, "fsr1_rcas.comp", SpatialShaderSource.rcasSource(),
                new SpatialComputePass.Binding[] {SpatialComputePass.Binding.COMBINED, SpatialComputePass.Binding.STORAGE}, 32);
            LOGGER.info("[VKB-SPATIAL] [VKB-AUTOTEST] fsr1=pipelines_created ms={} source=ffx_fsr1.h(FidelityFX SDK v2.3.0)",
                (System.nanoTime() - t0) / 1_000_000L);
        }
        if (backend == UpscalerBackend.NIS && nis == null) {
            nis = new SpatialComputePass(device, "nis_scaler.comp", SpatialShaderSource.nisSource(),
                new SpatialComputePass.Binding[] {SpatialComputePass.Binding.SAMPLER, SpatialComputePass.Binding.SAMPLED,
                    SpatialComputePass.Binding.STORAGE}, NisConstants.BYTES);
            LOGGER.info("[VKB-SPATIAL] [VKB-AUTOTEST] nis=pipeline_created ms={} source=NIS_Scaler.h(NIS SDK v1.0.3)",
                (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static long sampler(int filter) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(filter).minFilter(filter).mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .maxLod(0.0f);
            LongBuffer p = stack.mallocLong(1);
            int r = VK10.vkCreateSampler(device, info, null, p);
            if (r != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler failed: " + r);
            }
            return p.get(0);
        }
    }

    /** Teardown (the caller waited for the device to be idle). */
    public static void cleanup() {
        if (device == null) {
            return;
        }
        for (SpatialComputePass p : new SpatialComputePass[] {easu, rcas, nis}) {
            if (p != null) {
                p.destroy();
            }
        }
        easu = null;
        rcas = null;
        nis = null;
        if (linearSampler != 0L) {
            VK10.vkDestroySampler(device, linearSampler, null);
            linearSampler = 0L;
        }
        if (nearestSampler != 0L) {
            VK10.vkDestroySampler(device, nearestSampler, null);
            nearestSampler = 0L;
        }
        device = null;
        failedFor = null;
        lastLogged = null;
    }
}
