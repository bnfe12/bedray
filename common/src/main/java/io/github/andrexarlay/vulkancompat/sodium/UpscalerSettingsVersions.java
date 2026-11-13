package io.github.andrexarlay.vulkancompat.sodium;

import io.github.andrexarlay.vulkanbridge.api.LibraryDescriptor;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.framegen.StreamlineBootstrap;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;

import java.util.EnumMap;
import java.util.Map;

/** Copies the DLL versions already inspected by the native library manager. */
final class UpscalerSettingsVersions {
    private UpscalerSettingsVersions() {}

    static Map<UpscalerBackend, String> installedVersions() {
        Map<UpscalerBackend, String> versions = new EnumMap<>(UpscalerBackend.class);
        if (!VulkanBridge.isVulkan()) return versions;
        try {
            var libraries = VulkanBridge.libraries();
            var dlss = libraries.resolve(LibraryDescriptor.DLSS.id());
            var xess = libraries.resolve(LibraryDescriptor.XESS.id());
            if (!StreamlineBootstrap.dlssViaStreamline()) {
                versions.put(UpscalerBackend.DLSS, dlss.versions().get("nvngx_dlss.dll"));
            }
            versions.put(UpscalerBackend.XESS, xess.versions().get("libxess.dll"));
        } catch (IllegalStateException unavailable) {
            // Settings can be constructed before the native manager has a game directory.
        }
        return versions;
    }
}
