package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserMode;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangDeviceCapabilities;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntimePool;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the AOT Slang primary pass and owns this renderer's handle on the device's one native core, leased from
 * {@link SlangRuntimePool}. The pool is what keeps it to one core per device: a second {@code BedrayCore} on the same
 * device faults inside the driver on its first Vulkan call, so this owner leases rather than holds, and hands the lease
 * back only after the atmosphere released its recordings on the same core.
 */
final class RtPassFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    private SlangRuntime pathSlangRuntime;
    private SlangRuntimePool.Lease pathSlangLease;
    private boolean pathSlangUnavailable;
    private String pathSlangUnavailableReason = "Slang path runtime is not initialized";
    private boolean pathSlangUnavailableLogged;

    String unavailableReason() { return pathSlangUnavailableReason; }
    boolean hasRuntime() { return pathSlangRuntime != null; }

    /**
     * Creates the AOT Slang path (or its ray profile) or reports why it cannot run. There is no second renderer to fall
     * back to: a missing Slang artifact fails the frame, and {@link RtGeometryGate#markFailed} records the reason and
     * turns ray tracing off for the session.
     */
    RtPass createSlangPathPass(VkDevice device, boolean gbuffer, boolean rayMode, boolean worldReady,
                               DenoiserMode denoiseMode, boolean wantsEntityMotion) {
        String debugView = RtConfig.currentDebugView();
        if (!debugView.isEmpty()) {
            pathSlangUnavailableReason = "debug view '" + debugView + "' is not implemented in the Slang path";
            logSlangUnavailable(pathSlangUnavailableReason);
            return null;
        }
        VulkanDevice vulkanDevice = VulkanCapabilityChecker.getVulkanDevice();
        if (vulkanDevice == null) {
            pathSlangUnavailableReason = "VulkanDevice is not available";
            logSlangUnavailable(pathSlangUnavailableReason);
            return null;
        }
        try {
            SlangRuntime runtime = pathSlangRuntime(vulkanDevice);
            String problem = RtSlangPathPass.supportProblem(runtime, gbuffer);
            if (problem == null && RtConfig.SLANG_PATH_TRACE_CPP && gbuffer && wantsEntityMotion
                && (denoiseMode.isNrd() || denoiseMode == DenoiserMode.DLSS_RR)
                && !runtime.library().hasNativeMotion()) {
                problem = "native denoiser motion requires a matching core with NRD_MOTION";
            }
            if (problem != null) {
                pathSlangUnavailableReason = problem;
                logSlangUnavailable(problem);
                return null;
            }
            if (!worldReady) {
                pathSlangUnavailableReason = "the RT world is not initialized";
                logSlangUnavailable(pathSlangUnavailableReason);
                return null;
            }
            RtSlangPathPass slang = new RtSlangPathPass(device, runtime, gbuffer, rayMode);
            LOGGER.info("[VKB-RT] {} on AOT Slang passes {}+{} (gbuffer={})",
                rayMode ? "ray effects" : "path tracing",
                RtPathTraceSlangPass.PASS_NAME, RtSlangPathPass.DISPLAY_PASS, gbuffer);
            return slang;
        } catch (RtFailure failure) {
            pathSlangUnavailableReason = failure.result();
            throw failure;
        } catch (RuntimeException e) {
            String reason = LogPaths.show(e.getMessage()) == null ? e.getClass().getSimpleName() : LogPaths.show(e.getMessage());
            pathSlangUnavailableReason = reason;
            throw new RtFailure(rayMode ? "slang_ray" : "slang_path", reason, e);
        }
    }

    SlangRuntime pathSlangRuntime(VulkanDevice vulkanDevice) {
        if (pathSlangRuntime != null && !pathSlangRuntime.isClosed()) {
            return pathSlangRuntime;
        }
        if (pathSlangUnavailable) {
            throw new IllegalStateException(pathSlangUnavailableReason);
        }
        try {
            int enabledMask = SlangDeviceCapabilities.enabledMask(vulkanDevice);
            pathSlangLease = SlangRuntimePool.acquire(vulkanDevice);
            SlangRuntime runtime = pathSlangLease.runtime();
            pathSlangRuntime = runtime;
            LOGGER.info("[VKB-RT] Slang path runtime ready enabled_mask=0x{}", Integer.toHexString(enabledMask));
            return runtime;
        } catch (RuntimeException e) {
            pathSlangUnavailable = true;
            pathSlangUnavailableReason = LogPaths.show(e.getMessage()) == null ? e.getClass().getSimpleName() : LogPaths.show(e.getMessage());
            throw e;
        }
    }

    private void logSlangUnavailable(String reason) {
        if (!pathSlangUnavailableLogged) {
            pathSlangUnavailableLogged = true;
            LOGGER.info("[VKB-RT] Slang path tracing unavailable: {}", reason);
        }
    }

    /** Hands the leased core back to the pool; the caller must have released the atmosphere recordings first. */
    void closeLease() {
        pathSlangLease.close();
        pathSlangLease = null;
        pathSlangRuntime = null;
    }

    void resetUnavailable() {
        pathSlangUnavailable = false;
        pathSlangUnavailableLogged = false;
        pathSlangUnavailableReason = "Slang path runtime is not initialized";
    }
}