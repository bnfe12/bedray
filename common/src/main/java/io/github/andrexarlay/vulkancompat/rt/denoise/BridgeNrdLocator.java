package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkanbridge.api.LibraryResolution;
import io.github.andrexarlay.vulkanbridge.api.NativeLibraries;

import java.nio.file.Path;

/**
 * Finds NRD.dll and vkb_nrd.dll through the Bedray Core {@link NativeLibraries} (descriptor id {@code nrd}); the DLLs are never
 * located by guessing paths (docs/api/native-libraries.md, rule 1 of the security model). The {@code NativeLibraries} instance is
 * passed in by the caller, so this class has no loader dependency.
 */
public final class BridgeNrdLocator {
    public static final String LIBRARY_ID = "nrd";
    public static final String NRD_DLL = "NRD.dll";
    public static final String WRAPPER_DLL = "vkb_nrd.dll";

    private BridgeNrdLocator() {
    }

    public static NrdAvailability locate(NativeLibraries libs) {
        LibraryResolution r = libs.resolve(LIBRARY_ID);
        Path nrd = r.file(NRD_DLL).orElse(null);
        Path wrapper = r.file(WRAPPER_DLL).orElse(null);
        return NrdAvailability.from(r.usable(), r.status().state() + ": " + r.status().detail(), nrd, wrapper);
    }
}
