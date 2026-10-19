package io.github.andrexarlay.vulkancompat.rt.denoise;

import java.nio.file.Path;

/**
 * Result of looking for the NRD files. Loader independent: it carries only paths and a reason, the natives directory
 * comes from the caller (Bedray Core {@code NativeLibraries}, see {@link BridgeNrdLocator}).
 *
 * @param usable      both {@code NRD.dll} and {@code vkb_nrd.dll} were found and passed the Core checks
 * @param nrdDll      NRD.dll (NVIDIA, the user installs it); null when not usable
 * @param wrapperDll  vkb_nrd.dll (our wrapper, the user builds it with native/nrd_bridge/build.bat); null when not usable
 * @param reason      human readable; for an unusable result it says what is missing (goes to the log)
 */
public record NrdAvailability(boolean usable, Path nrdDll, Path wrapperDll, String reason) {
    public NrdAvailability {
        if (reason == null) {
            throw new IllegalArgumentException("reason is required");
        }
        if (usable && (nrdDll == null || wrapperDll == null)) {
            throw new IllegalArgumentException("usable needs both paths");
        }
    }

    public static NrdAvailability unavailable(String reason) {
        return new NrdAvailability(false, null, null, reason);
    }

    /**
     * Combines the library state of Bedray Core: {@code libraryUsable}/{@code detail} describe the descriptor "nrd"
     * (NRD.dll required, vkb_nrd.dll optional there), {@code nrdDll}/{@code wrapperDll} are the resolved files.
     */
    public static NrdAvailability from(boolean libraryUsable, String detail, Path nrdDll, Path wrapperDll) {
        if (!libraryUsable) {
            return unavailable("NRD.dll not usable: " + detail);
        }
        if (nrdDll == null) {
            return unavailable("NRD.dll missing");
        }
        if (wrapperDll == null) {
            return unavailable("vkb_nrd.dll (our wrapper) is not installed; build it with native/nrd_bridge/build.bat and install it next to NRD.dll");
        }
        return new NrdAvailability(true, nrdDll, wrapperDll, detail);
    }
}
