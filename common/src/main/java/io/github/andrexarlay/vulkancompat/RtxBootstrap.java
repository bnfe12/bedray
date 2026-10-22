package io.github.andrexarlay.vulkancompat;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkanbridge.platform.Platforms;
import io.github.andrexarlay.vulkancompat.autotest.RtxAutotest;
import io.github.andrexarlay.vulkancompat.rt.denoise.NrdDeviceRequirements;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.RtDeviceRequirements;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssDeviceRequirements;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr.FsrDeviceRequirements;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessDeviceRequirements;
import io.github.andrexarlay.vulkancompat.rt.world.RtShaderCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * All startup logic of the Bedray mod (loader-neutral, module common). A loader entry point only calls
 * {@link #earlyInit()}. It registers OUR device requirements (hardware ray tracing, XeSS, DLSS, FSR, NRD storage images) as late
 * providers in the public registry of Bedray Core and the autotest scenarios {@code settings} and {@code sun}, exactly
 * like a third-party mod would. The registry has to be filled before the engine creates its VkInstance/VkDevice; Bedray Core
 * runs the providers at that moment, before it reads the instance apiVersion and freezes the registry.
 * <p>
 * The game directory (XeSS checks libxess.dll there) comes from Bedray Core seam {@code Platforms.get().gameDir()}, NOT from
 * {@code Minecraft.getInstance()}, which must not be read this early. The registration itself is
 * done through {@code registerEarlyHook} (Core porting notes): it runs immediately when Bedray Core already passed its
 * early point and otherwise at it.
 */
public final class RtxBootstrap {
    public static final String MOD_ID = "bedray";
    public static final String RT_PROVIDER_ID = "bedray.hardware_ray_tracing";
    public static final String XESS_PROVIDER_ID = "bedray.xess";
    public static final String DLSS_PROVIDER_ID = "bedray.dlss";
    public static final String NRD_PROVIDER_ID = "bedray.nrd";
    public static final String FSR_PROVIDER_ID = "bedray.fsr";
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx");

    private RtxBootstrap() {}

    /** Called by the loader entry point. Touches no Minecraft class. */
    public static void earlyInit() {
        ensureVulkanOptionsEarly();
        Platforms.get().registerEarlyHook(RtxBootstrap::registerProvidersAndScenarios);
    }

    private static void ensureVulkanOptionsEarly() {
        try {
            Path gameDir = Platforms.get().gameDir();
            if (gameDir == null) return;
            Path optionsFile = gameDir.resolve("options.txt");
            if (!Files.exists(optionsFile)) return;
            List<String> lines = Files.readAllLines(optionsFile, StandardCharsets.UTF_8);
            List<String> newLines = new ArrayList<>(lines.size() + 2);
            boolean hasBackend = false;
            boolean hasClean = false;
            boolean changed = false;
            for (String line : lines) {
                if (line.startsWith("preferredGraphicsBackend:")) {
                    hasBackend = true;
                    if (!line.equals("preferredGraphicsBackend:\"vulkan\"")) {
                        newLines.add("preferredGraphicsBackend:\"vulkan\"");
                        changed = true;
                        continue;
                    }
                } else if (line.startsWith("startedCleanly:")) {
                    hasClean = true;
                    if (!line.equals("startedCleanly:true")) {
                        newLines.add("startedCleanly:true");
                        changed = true;
                        continue;
                    }
                }
                newLines.add(line);
            }
            if (!hasBackend) {
                newLines.add("preferredGraphicsBackend:\"vulkan\"");
                changed = true;
            }
            if (!hasClean) {
                newLines.add("startedCleanly:true");
                changed = true;
            }
            if (changed) {
                Files.write(optionsFile, newLines, StandardCharsets.UTF_8);
                LOGGER.info("[VKB-RTX] ensureVulkanOptionsEarly: locked options.txt to preferredGraphicsBackend:\"vulkan\" and startedCleanly:true");
            }
        } catch (Throwable t) {
            LOGGER.warn("[VKB-RTX] ensureVulkanOptionsEarly warning: {}", t.getMessage());
        }
    }

    private static void registerProvidersAndScenarios() {
        DeviceFeatureRegistry registry = VulkanBridge.deviceFeatures();
        registry.addLateProvider(RT_PROVIDER_ID, RtDeviceRequirements::register);
        registry.addLateProvider(XESS_PROVIDER_ID,
            r -> XessDeviceRequirements.register(r, () -> Platforms.get().gameDir().toAbsolutePath().normalize()));
        // DLSS: device group + instance extensions (VK_KHR_get_physical_device_properties2), only when DLSS was requested
        registry.addLateProvider(DLSS_PROVIDER_ID, DlssDeviceRequirements::register);
        // FSR: core features used by the FidelityFX VK shaders (storage image formats, FP16), no extensions; official DLL only
        registry.addLateProvider(FSR_PROVIDER_ID, FsrDeviceRequirements::register);
        // only adds the storage-image features when -Dvkrtx.denoiser=nrd* is set; a default run leaves the device unchanged
        registry.addLateProvider(NRD_PROVIDER_ID, NrdDeviceRequirements::register);
        RtShaderCompiler.prewarmAsync();
        RtxAutotest.registerAll();
        LOGGER.info("[VKB-RTX] late device-requirement providers added, autotest scenarios registered, Bedray core API {}",
            VulkanBridge.apiVersion());
    }
}
