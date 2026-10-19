package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The FSR upscaler version the user wants. The ffx-api picks a provider per create call; {@code ffxOverrideVersion} with an
 * id from {@code ffxQueryDescGetVersions} forces one (ffx_api.h l.85-101, ffx_api.cpp GetVersionOverride). The Vulkan DLL of
 * FidelityFX SDK v1.1.4 contains two upscaler providers (ffx_provider.cpp l.43-53: FSR3Upscale first, then FSR2), whose
 * names are their version strings ("3.1.x", "2.x.y"). FSR 4 is not offered: on Vulkan the ffx-api has no driver-side
 * (external) providers (backends.cpp GetDevice returns nullptr for the VK backend, and GetExternalProviders only queries
 * the AMD D3D12 driver extension), and AMD's FSR 4 is published only as a DX12 upscaler DLL.
 * <p>
 * {@link #AUTO}: no override, the API default (the newest internal provider, FSR 3.1.x for v1.1.4).
 */
public enum FsrVersion {
    AUTO("Auto", null),
    FSR_3_1("FSR 3.1", "3.1."),
    FSR_2("FSR 2", "2.");

    private final String displayName;
    private final String namePrefix;

    FsrVersion(String displayName, String namePrefix) {
        this.displayName = displayName;
        this.namePrefix = namePrefix;
    }

    public String displayName() {
        return displayName;
    }

    /** True when the provider version name reported by the DLL belongs to this choice (AUTO matches nothing explicitly). */
    public boolean matches(String providerName) {
        return namePrefix != null && providerName != null && providerName.startsWith(namePrefix);
    }

    /** Provider of {@code available} for this choice; empty for AUTO (no override) or when the DLL does not have it. */
    public Optional<FfxNative.ProviderVersion> pick(List<FfxNative.ProviderVersion> available) {
        if (this == AUTO) {
            return Optional.empty();
        }
        return available.stream().filter(v -> matches(v.name())).findFirst();
    }

    /** Whether the choice can be made with the providers of the loaded DLL (AUTO always). */
    public boolean isAvailableIn(List<FfxNative.ProviderVersion> available) {
        return this == AUTO || pick(available).isPresent();
    }

    /** {@code auto}, {@code 3.1}, {@code 2}, or the enum name. */
    public static FsrVersion parse(String raw) {
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "AUTO", "" -> AUTO;
            case "3.1", "3", "FSR3", "FSR_3_1", "FSR3.1" -> FSR_3_1;
            case "2", "FSR2", "FSR_2" -> FSR_2;
            default -> throw new IllegalArgumentException("unknown FSR version '" + raw + "' (auto, 3.1, 2)");
        };
    }
}
