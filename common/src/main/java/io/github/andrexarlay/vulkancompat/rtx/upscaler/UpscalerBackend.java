package io.github.andrexarlay.vulkancompat.rtx.upscaler;

/**
 * Super resolution upscaler backends known to the Bedray.
 * <p>
 * {@link #BILINEAR} (stretch of the scaled 3D target by a linear blit), {@link #XESS} (xessVKExecute, needs
 * libxess.dll) and {@link #DLSS} (NGX evaluate through vkb_dlss.dll, needs the libraries resolved by Bedray Core's
 * {@code NativeLibraries} and an NVIDIA RTX GPU) and {@link #FSR} (ffxDispatch through the official AMD FidelityFX
 * Vulkan DLL {@code amd_fidelityfx_vk.dll} resolved by Bedray Core, FSR 3.1.x / 2.x upscaler; FSR Frame Generation is STUB)
 * are implemented.
 */
public enum UpscalerBackend {
    BILINEAR("Bilinear (Built-in)", "bedray.backend.bilinear"),
    // FSR upscaler: FsrBackend/FsrRuntime through the official ffx-api Vulkan DLL (FidelityFX SDK v1.1.4); switches live.
    FSR("FSR (AMD)", "bedray.backend.fsr"),
    // DLSS Super Resolution: real NGX dispatch (DlssBackend/DlssRuntime through our vkb_dlss.dll); needs nvngx_dlss.dll +
    // vkb_dlss.dll from Bedray Core's NativeLibraries and an NVIDIA RTX GPU; switches live (UpscalerPreregistration). Frame Generation / Ray Reconstruction: STUB.
    DLSS("DLSS (NVIDIA)", "bedray.backend.dlss"),
    // XeSS: real dispatch exists (XessBackend/XessRuntime, log xess=dispatch ok); needs libxess.dll; switches live (UpscalerPreregistration).
    XESS("XeSS (Intel)", "bedray.backend.xess"),
    // Built-in spatial upscalers (compute shaders in this mod, no vendor DLL, any Vulkan GPU): AMD FidelityFX FSR 1
    // (EASU + optional RCAS, ffx_fsr1.h, MIT) and NVIDIA Image Scaling (NVScaler, NIS SDK v1.0.3, MIT). SpatialUpscaler.
    FSR1("FSR 1 (built-in)", "bedray.backend.fsr1"),
    NIS("NIS (built-in)", "bedray.backend.nis");

    private final String displayName;
    private final String translationKey;

    UpscalerBackend(String displayName, String translationKey) {
        this.displayName = displayName;
        this.translationKey = translationKey;
    }

    /**
     * Built into this mod (no native library, no device extension, no temporal history): BILINEAR, FSR1, NIS. Never needs a
     * restart and is always available on the Vulkan backend.
     */
    public boolean isBuiltIn() {
        return this == BILINEAR || this == FSR1 || this == NIS;
    }

    /** FSR1 / NIS: built-in spatial upscalers run by SpatialUpscaler at the end of the world phase. */
    public boolean isSpatial() {
        return this == FSR1 || this == NIS;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    /**
     * True only for backends with a real implementation. For DLSS the answer also needs the libraries resolved by the
     * Core and a GPU that is not known to be non-NVIDIA ({@code DlssAvailability.isUsable()}); XeSS library presence is
     * checked separately ({@code UpscalerSelection.status}).
     */
    public boolean isImplemented() {
        return this == BILINEAR || this == XESS || this == FSR1 || this == NIS
            || (this == DLSS && io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability.isUsable())
            || (this == FSR && io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrLibrary.isUsable());
    }
}
