package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Whether DLSS can be used at all: the user-provided libraries and the GPU. Nothing is loaded here.
 * <p>
 * Libraries come only from Bedray Core's {@code NativeLibraries.resolve("dlss")} (Bedray Core): the DLL paths used later by {@link DlssNative} are exactly the paths returned by resolve
 * (project rules on loading vendor DLLs). Required: {@code nvngx_dlss.dll} (NVIDIA DLSS model library, loaded by NGX from the
 * same folder) and {@code vkb_dlss.dll} (our wrapper, built with {@code native/dlss_bridge/build.bat}; optional file of the
 * {@code dlss} descriptor, required for us).
 * <p>
 * The GPU part is a pre-device verdict only (vendor id when the device exists). Whether the GPU really is an RTX that supports
 * DLSS SR is decided by NGX ({@code SuperSampling.Available}, {@link DlssRuntime}); a negative answer there is reported
 * and disables DLSS for the session.
 */
public final class DlssAvailability {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssAvailability");

    public static final String LIBRARY_ID = "dlss";
    public static final String WRAPPER_DLL = "vkb_dlss.dll";
    public static final String NGX_DLL = "nvngx_dlss.dll";
    private static final int VENDOR_NVIDIA = 0x10DE;

    /** Library verdict. {@code wrapperDll} and {@code featureDir} are set only when {@code usable}. */
    public record Library(boolean usable, String reason, Path wrapperDll, Path featureDir) {
        static Library unusable(String reason) {
            return new Library(false, reason, null, null);
        }
    }

    private static Supplier<LibraryResolution> resolver = () -> libraries().resolve(LIBRARY_ID);
    private static Supplier<Boolean> nvidiaGpu = DlssAvailability::vendorFromDevice;
    private static Library cached;
    private static boolean listenerRegistered;

    private static Library viaStreamline;

    private DlssAvailability() {}

    private static NativeLibraries libraries() {
        NativeLibraries libs = VulkanBridge.libraries();
        if (!listenerRegistered) {
            listenerRegistered = true;
            libs.addListener(event -> invalidate());
        }
        return libs;
    }

    /** Result of {@code resolve("dlss")}, cached until a library event or {@link #invalidate()}. */
    public static synchronized Library library() {
        if (cached == null) {
            cached = evaluate();
            LOGGER.info("[VKB-DLSS] library usable={} reason={} wrapper={} featureDir={}",
                cached.usable(), cached.reason(), io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(cached.wrapperDll()), io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(cached.featureDir()));
        }
        if (io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap.dlssViaStreamline()) {
            // Streamline owns NGX in this process (frame generation): DLSS SR and Ray Reconstruction go through its sl.dlss / sl.dlss_d
            // plugins, implemented behind the same vkb_dlss.h ABI in vkb_sl.dll (native/sl_bridge/src/vkb_sl_dlss.cpp).
            Path shim = io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenEarly.shimPath();
            if (viaStreamline == null) {
                viaStreamline = new Library(true, "streamline plugins (sl.dlss, sl.dlss_d)", shim,
                    io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenEarly.slDir());
                LOGGER.info("[VKB-DLSS] library via Streamline: wrapper={} (raw NGX is not usable next to Streamline frame generation)", io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(shim));
            }
            return viaStreamline;
        }
        return cached;
    }

    private static Library evaluate() {
        LibraryResolution r;
        try {
            r = resolver.get();
        } catch (IllegalStateException e) {
            // VulkanBridge.libraries() throws outside the game (no game directory): reported, never treated as "present".
            return Library.unusable("native library manager unavailable: "
                + io.github.andrexarlay.vulkanbridge.platform.LogPaths.show(e.getMessage()));
        }
        if (!r.usable()) {
            return Library.unusable("dlss library " + r.status().state() + ": " + r.status().detail());
        }
        Path wrapper = r.file(WRAPPER_DLL).orElse(null);
        if (wrapper == null) {
            return Library.unusable(WRAPPER_DLL + " not installed (build native/dlss_bridge/build.bat, then libraries().install(\"dlss\", <vkb_dlss.dll>))");
        }
        Path ngx = r.file(NGX_DLL).orElse(null);
        if (ngx == null || ngx.getParent() == null) {
            return Library.unusable(NGX_DLL + " not resolved");
        }
        return new Library(true, "ok" + (r.legacy() ? " (legacy folder)" : ""), wrapper, ngx.getParent());
    }

    public static synchronized void invalidate() {
        cached = null;
    }

    /**
     * True when the vendor id of the engine device is known and is not NVIDIA, i.e. DLSS cannot work;
     * false when the device is NVIDIA or not created yet (unknown is not a veto here: the real answer is NGX's).
     */
    public static boolean gpuRulesOutDlss() {
        Boolean nv = nvidiaGpu.get();
        return nv != null && !nv;
    }

    /** Library present and the GPU is not known to be non-NVIDIA. Does not mean DLSS works (see {@link DlssRuntime}). */
    public static boolean isUsable() {
        return library().usable() && !gpuRulesOutDlss() && !DlssRuntime.isUnsupportedOnThisSystem();
    }

    /** Why {@link #isUsable()} is false (library reason, GPU verdict or NGX's answer), for log lines. */
    public static String unusableReason() {
        Library lib = library();
        if (!lib.usable()) {
            return lib.reason();
        }
        if (DlssRuntime.isUnsupportedOnThisSystem()) {
            return DlssRuntime.unsupportedReason();
        }
        return gpuRulesOutDlss() ? "GPU vendor is not NVIDIA" : "usable";
    }

    /** Null while the device does not exist yet. */
    private static Boolean vendorFromDevice() {
        if (VulkanCapabilityChecker.getVulkanDevice() == null) {
            return null;
        }
        return VulkanCapabilityChecker.getGpuVendorId() == VENDOR_NVIDIA;
    }

    /** Test hook: replaces {@code resolve("dlss")}; pass null to restore Bedray Core resolver. */
    public static synchronized void setResolverForTesting(Supplier<LibraryResolution> replacement) {
        resolver = replacement != null ? replacement : () -> libraries().resolve(LIBRARY_ID);
        cached = null;
    }

    /** Test hook: replaces the vendor check ({@code null} supplier result = device unknown); null restores the real one. */
    public static synchronized void setNvidiaForTesting(Supplier<Boolean> replacement) {
        nvidiaGpu = replacement != null ? replacement : DlssAvailability::vendorFromDevice;
    }
}
