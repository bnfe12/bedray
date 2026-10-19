package io.github.andrexarlay.vulkancompat.rt.denoise;

import org.lwjgl.system.FunctionProvider;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-wide state of vkb_nrd.dll: it can be initialised only once per process (vkb_nrd_init fails with ALREADY_INIT the second time),
 * while the RT world may be torn down and created again. Holds the loaded wrapper and the library info; created on first use.
 * Render thread only.
 */
final class NrdRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-NRD");

    private static NrdNative nativeLib;
    private static NrdNative.LibraryInfo info;
    private static String failure;

    private NrdRuntime() {
    }

    /** Failure text of an earlier attempt, or null. A failed init is final for the session (no retry loop). */
    static String failure() {
        return failure;
    }

    static NrdNative nativeLib() {
        return nativeLib;
    }

    static NrdNative.LibraryInfo info() {
        return info;
    }

    /**
     * Loads and initialises vkb_nrd.dll on first call.
     *
     * @throws NrdException with the reason; the same exception text is returned by every later call
     */
    static void ensureInitialised(VkDevice device, NrdAvailability availability) {
        if (failure != null) {
            throw new NrdException("init", "earlier failure: " + failure, null);
        }
        if (nativeLib != null) {
            return;
        }
        try {
            if (!availability.usable()) {
                throw new NrdException("locate", availability.reason(), null);
            }
            NrdNative n = NrdNative.load(availability.wrapperDll());
            FunctionProvider fp = VK.getFunctionProvider();
            if (fp == null) {
                throw new NrdException("init", "LWJGL VK function provider is not created (vkGetInstanceProcAddr unavailable)", null);
            }
            long gipa = fp.getFunctionAddress("vkGetInstanceProcAddr");
            long gdpa = device.getCapabilities().vkGetDeviceProcAddr;
            if (gipa == 0L || gdpa == 0L) {
                throw new NrdException("init", "vkGetInstanceProcAddr/vkGetDeviceProcAddr address is 0 (gipa=" + gipa + " gdpa=" + gdpa + ")", null);
            }
            long instance = device.getPhysicalDevice().getInstance().address();
            NrdNative.LibraryInfo li = n.init(instance, device.getPhysicalDevice().address(), device.address(), gipa, gdpa, availability.nrdDll());
            nativeLib = n;
            info = li;
            LOGGER.info("[VKB-NRD] loaded path={} nrd={} normEnc={} roughEnc={} normFormat={} wrapper_api=0x{} required_features=0x{}",
                io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(availability.nrdDll()), li, li.normalEncoding(), li.roughnessEncoding(), li.normalRoughnessVkFormat(),
                Integer.toHexString(n.apiVersion()), Integer.toHexString(n.requiredFeatures()));
        } catch (NrdException e) {
            failure = e.getMessage();
            throw e;
        }
    }
}
