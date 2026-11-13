package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Provider family selected from AMD's Vulkan version query. AUTO uses the best eligible family. */
public enum FsrVersion {
    AUTO("Auto", null),
    FSR_4("FSR 4", "4."),
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
        if (namePrefix == null || providerName == null) return false;
        String version = providerName.strip().replaceFirst(
            "(?i)^(?:AMD\\s+)?(?:FSR(?:\\s+Upscaling)?|(?:FidelityFX\\s+)?Super Resolution)\\s*", "");
        return version.startsWith(namePrefix);
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

    /** Accepts the persisted enum name and the human-readable version. */
    public static FsrVersion parse(String raw) {
        String v = raw.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "AUTO", "" -> AUTO;
            case "4", "4.1", "FSR4", "FSR_4", "FSR4.1" -> FSR_4;
            case "3.1", "3", "FSR3", "FSR_3_1", "FSR3.1" -> FSR_3_1;
            case "2", "FSR2", "FSR_2" -> FSR_2;
            default -> throw new IllegalArgumentException("unknown FSR version '" + raw + "' (auto, 4, 3.1, 2)");
        };
    }
}
