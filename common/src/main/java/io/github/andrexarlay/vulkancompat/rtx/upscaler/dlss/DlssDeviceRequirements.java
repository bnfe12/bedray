package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import io.github.andrexarlay.vulkancompat.rt.denoise.DenoiserConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import org.lwjgl.vulkan.VK11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * DLSS device requirements, registered as a late provider (like XeSS/RT) in the Bedray Core registry when DLSS was
 * requested ({@link DlssConfig}) or, by default, preregistered so that DLSS can be selected at runtime
 * ({@link UpscalerPreregistration}), and in both cases only when the libraries are usable ({@link DlssAvailability});
 * otherwise the device is created exactly as without DLSS.
 * <p>
 * Extension list: result of {@code NVSDK_NGX_VULKAN_GetFeatureDeviceExtensionRequirements} (through
 * {@code vkb_dlss_get_required_device_extensions}) of the probe {@code native/dlss_bridge/build/dlss_probe.exe} on an
 * RTX 5060 Ti, DLSS 310.9.1, NGX API 0x15, driver 617.14:
 * {@code VK_NVX_binary_import, VK_NVX_image_view_handle, VK_KHR_buffer_device_address, VK_KHR_push_descriptor}.
 * Device extensions cannot be queried at provider time (no physical device yet), so they are fixed here and
 * {@link DlssRuntime} compares them with the live NGX answer for the real device; a mismatch is reported as FAILED
 * ({@code stage=required_device_ext}), never ignored. The instance extension NGX asks for
 * ({@code VK_KHR_get_physical_device_properties2}) is requested through Bedray Core ({@code requestInstanceExtensions},
 * Bedray Core): the DLSS Programming Guide (2.3.1) and nvsdk_ngx_vk.h tell the application to enable what
 * {@code NVSDK_NGX_VULKAN_GetFeatureInstanceExtensionRequirements} returns before the VkInstance is created, and the engine
 * creates a 1.2 instance with only the SDL surface extensions. Promotion to core in 1.1 is not treated as enabling it.
 * {@code bufferDeviceAddress} is the {@code VkPhysicalDeviceVulkan12Features} member that goes with
 * {@code VK_KHR_buffer_device_address} (the RT requirement uses the same struct alias).
 */
public final class DlssDeviceRequirements {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FeatureSets");

    public static final String ID = "VKB DLSS";
    public static final String INSTANCE_REQUEST_ID = "VKB DLSS instance";
    public static final Set<String> INSTANCE_EXTENSIONS = Set.of("VK_KHR_get_physical_device_properties2");
    public static final Set<String> DEVICE_EXTENSIONS = Set.of(
        "VK_NVX_binary_import", "VK_NVX_image_view_handle", "VK_KHR_buffer_device_address", "VK_KHR_push_descriptor");

    private DlssDeviceRequirements() {}

    public static DeviceRequirement requirement() {
        return new DeviceRequirement(
            ID,
            DEVICE_EXTENSIONS,
            List.of(FeatureRequest.core(FeatureRequest.VK12, "bufferDeviceAddress")),
            VK11.VK_API_VERSION_1_1,
            true,
            () -> Boolean.TRUE);
    }

    /**
     * Registers the group (and the instance extension) when DLSS was requested, or, by default, whenever its libraries are
     * usable although DLSS is not selected (preregistration so that DLSS can be selected at runtime, see
     * {@link UpscalerPreregistration}). The GPU is not known at provider time; Bedray Core drops the optional set on a GPU
     * without the NVX extensions, and {@link DlssRuntime} then reports {@code required_device_ext} missing. The reason is
     * logged when nothing is registered.
     */
    public static void register(DeviceFeatureRegistry registry) {
        boolean requested = DlssConfig.requestedQuality() != null || DenoiserConfig.requested().isDlssRr();
        register(registry, requested, UpscalerPreregistration.enabled());
    }

    /** Same as {@link #register(DeviceFeatureRegistry)} with the two inputs of the decision given explicitly (tests). */
    public static void register(DeviceFeatureRegistry registry, boolean requested, boolean preregisterEnabled) {
        UpscalerPreregistration.Decision decision = UpscalerPreregistration.decide(requested, preregisterEnabled);
        if (decision == UpscalerPreregistration.Decision.SKIP) {
            UpscalerPreregistration.record(UpscalerBackend.DLSS, false);
            LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] upscaler_prereg backend=DLSS registered=false reason=disabled (-D{}=false, DLSS not requested: selecting it needs a restart)",
                UpscalerPreregistration.PROPERTY);
            return;
        }
        DlssAvailability.Library lib = DlssAvailability.library();
        if (!lib.usable()) {
            UpscalerPreregistration.record(UpscalerBackend.DLSS, false);
            if (requested) {
                LOGGER.warn("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=device_hook result=library_unusable reason={} (DLSS FeatureSet NOT added, device unchanged)",
                    lib.reason());
            } else {
                LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] upscaler_prereg backend=DLSS registered=false reason=library_unusable detail={}", lib.reason());
            }
            return;
        }
        registry.requestInstanceExtensions(INSTANCE_REQUEST_ID, INSTANCE_EXTENSIONS);
        registry.register(requirement());
        UpscalerPreregistration.record(UpscalerBackend.DLSS, true);
        LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] upscaler_prereg backend=DLSS registered=true reason={} instance_ext={} device_ext={}",
            decision == UpscalerPreregistration.Decision.REGISTER_REQUESTED ? "requested" : "preregistered",
            INSTANCE_EXTENSIONS, DEVICE_EXTENSIONS);
    }
}
