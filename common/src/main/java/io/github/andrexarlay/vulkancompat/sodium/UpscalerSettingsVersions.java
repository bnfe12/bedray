package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.core.NativeFiles;
import io.github.andrexarlay.vulkancompat.rtx.framegen.FrameGenEarly;
import io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssAvailability;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;

import java.util.EnumMap;
import java.util.Map;

/** Display metadata for the selected library paths. Does not load any DLLs. */
final class UpscalerSettingsVersions {
    private UpscalerSettingsVersions() {}

    static Map<UpscalerBackend, String> installedVersions() {
        Map<UpscalerBackend, String> versions = new EnumMap<>(UpscalerBackend.class);
        if (!VulkanBridge.isVulkan()) return versions;
        try {
            var libraries = VulkanBridge.libraries();
            String dlssVersion = StreamlineBootstrap.dlssViaStreamline()
                ? NativeFiles.version(FrameGenEarly.slDir().resolve(DlssAvailability.NGX_DLL))
                : libraries.resolve(LibraryDescriptor.DLSS.id()).versions().get(DlssAvailability.NGX_DLL);
            versions.put(UpscalerBackend.DLSS, dlssVersion);

            String xessVersion = XessRuntime.installedVersion();
            if (xessVersion == null) {
                xessVersion = libraries.resolve(LibraryDescriptor.XESS.id()).versions().get(XessConfig.DLL_NAME);
            }
            versions.put(UpscalerBackend.XESS, xessVersion);
        } catch (IllegalStateException unavailable) {
            // Settings can be constructed before the native manager has a game directory.
        }
        return versions;
    }
}
