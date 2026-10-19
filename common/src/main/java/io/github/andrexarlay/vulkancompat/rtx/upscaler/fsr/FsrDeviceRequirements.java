package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;

/**
 * Device features the FidelityFX Vulkan backend (SDK v1.1.4) needs from the engine device.
 * <p>
 * Evidence, not guesses:
 * <ul>
 * <li>The VK backend lists no mandatory device extension (ffx_vk.cpp {@code GetDeviceCapabilitiesVK}: VK_EXT_subgroup_size_control,
 *     VK_KHR_shader_float16_int8, VK_KHR_synchronization2 etc. are only probed as optional). BUT it probes them with
 *     {@code vkEnumerateDeviceExtensionProperties} / {@code vkGetPhysicalDeviceFeatures2} on the PHYSICAL device
 *     (ffx_vk.cpp l.1514-1516, l.2008-2020), i.e. what the GPU supports, not what the engine enabled: on a GPU with
 *     shaderFloat16 it selects the FP16 shader permutations even if the device was created without the feature.</li>
 * <li>SPIR-V capabilities of the 478 shader modules embedded in amd_fidelityfx_vk.dll (scan of the DLL for SPIR-V headers
 *     and OpCapability, 2026-10-11): Shader 478, StorageImageExtendedFormats 408, StorageImageWriteWithoutFormat 120,
 *     ImageQuery 108, Float16 106, Int16 76, GroupNonUniform 28, GroupNonUniformQuad 24, StorageImageReadWithoutFormat 4,
 *     GroupNonUniformArithmetic 4. No 16-bit storage capabilities, no extension instructions.</li>
 * </ul>
 * <li>Context creation (ffx_vk.cpp CreateBackendContextVK, "Setup Breadcrumbs data", l.1620-1663) calls
 *     {@code vkGetBufferMemoryRequirements2KHR} whenever the PHYSICAL device lists VK_KHR_dedicated_allocation. The pointer is
 *     fetched with {@code vkGetDeviceProcAddr(device, "vkGetBufferMemoryRequirements2KHR")} (l.1487), which is NULL unless
 *     the extension VK_KHR_get_memory_requirements2 is enabled on the device (core 1.1 does not make the KHR alias
 *     available). Observed: first in-game run crashed with EXCEPTION_ACCESS_VIOLATION at pc=0 inside ffxCreateContext
 *     (hs_err_pid24748, 2026-10-11). Hence VK_KHR_get_memory_requirements2 + VK_KHR_dedicated_allocation in the base group.
 *     On AMD GPUs the same code path uses vkCmdWriteBufferMarker(2)AMD (VK_AMD_buffer_marker, l.1714/4315) and device
 *     coherent memory types (VK_AMD_device_coherent_memory, l.1683): separate optional groups, dropped on other vendors.</li>
 * </ul>
 * Hence optional groups (the engine silently drops an unsupported optional set; "Enabling optional FeatureSet [..]"
 * in the log proves which one was kept):
 * <ul>
 * <li>"VKB FSR": VK_KHR_get_memory_requirements2, VK_KHR_dedicated_allocation, shaderStorageImageExtendedFormats, shaderStorageImageWriteWithoutFormat, shaderStorageImageReadWithoutFormat
 *     (VkPhysicalDeviceFeatures);</li>
 * <li>"VKB FSR FP16": shaderFloat16 (VkPhysicalDeviceVulkan12Features) + shaderInt16 (VkPhysicalDeviceFeatures), for the
 *     FP16 permutations the backend picks on GPUs that support shaderFloat16.</li>
 * <li>"VKB FSR AMD marker": VK_AMD_buffer_marker; "VKB FSR AMD coherent": VK_AMD_device_coherent_memory + deviceCoherentMemory.</li>
 * </ul>
 * Registered when FSR is requested before the device exists, or (preregistration, {@link UpscalerPreregistration}) whenever
 * the official library is usable, so FSR can be selected at runtime.
 */
public final class FsrDeviceRequirements {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FeatureSets");

    public static final String ID = "VKB FSR";
    public static final String ID_FP16 = "VKB FSR FP16";
    public static final String ID_AMD_MARKER = "VKB FSR AMD marker";
    public static final String ID_AMD_COHERENT = "VKB FSR AMD coherent";
    /** Device extensions of the base group; FsrRuntime refuses to create a context when the engine device lacks them. */
    public static final Set<String> BASE_EXTENSIONS = Set.of("VK_KHR_get_memory_requirements2", "VK_KHR_dedicated_allocation");

    private FsrDeviceRequirements() {}

    public static DeviceRequirement base() {
        return new DeviceRequirement(
            ID,
            BASE_EXTENSIONS,
            List.of(
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageExtendedFormats"),
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageWriteWithoutFormat"),
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageReadWithoutFormat")
            ),
            0,
            true,
            () -> Boolean.TRUE);
    }

    public static DeviceRequirement fp16() {
        return new DeviceRequirement(
            ID_FP16,
            Set.of(),
            List.of(
                FeatureRequest.core(FeatureRequest.VK12, "shaderFloat16"),
                FeatureRequest.core(FeatureRequest.VK10, "shaderInt16")
            ),
            0,
            true,
            () -> Boolean.TRUE);
    }

    public static DeviceRequirement amdMarker() {
        return new DeviceRequirement(ID_AMD_MARKER, Set.of("VK_AMD_buffer_marker"), List.of(), 0, true, () -> Boolean.TRUE);
    }

    public static DeviceRequirement amdCoherent() {
        return new DeviceRequirement(ID_AMD_COHERENT, Set.of("VK_AMD_device_coherent_memory"),
            List.of(FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceCoherentMemoryFeaturesAMD.class, "deviceCoherentMemory")),
            0, true, () -> Boolean.TRUE);
    }

    public static void register(DeviceFeatureRegistry registry) {
        register(registry, FsrConfig.requestedRequest() != null, UpscalerPreregistration.enabled(), FsrLibrary.library());
    }

    /** Decision with explicit inputs (tests). */
    public static void register(DeviceFeatureRegistry registry, boolean requested, boolean preregisterEnabled, FsrLibrary.Library lib) {
        UpscalerPreregistration.Decision decision = UpscalerPreregistration.decide(requested, preregisterEnabled);
        if (decision == UpscalerPreregistration.Decision.SKIP) {
            UpscalerPreregistration.record(UpscalerBackend.FSR, false);
            LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] upscaler_prereg backend=FSR registered=false reason=disabled (-D{}=false, FSR not requested: selecting it needs a restart)",
                UpscalerPreregistration.PROPERTY);
            return;
        }
        if (!lib.usable()) {
            UpscalerPreregistration.record(UpscalerBackend.FSR, false);
            if (requested) {
                LOGGER.warn("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage=device_hook result=library_unusable reason={} (FSR FeatureSets NOT added, device unchanged)", lib.reason());
            } else {
                LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] upscaler_prereg backend=FSR registered=false reason=library_unusable detail={}", lib.reason());
            }
            return;
        }
        registry.register(base());
        registry.register(fp16());
        registry.register(amdMarker());
        registry.register(amdCoherent());
        UpscalerPreregistration.record(UpscalerBackend.FSR, true);
        LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] upscaler_prereg backend=FSR registered=true reason={} groups=[{}, {}, {}, {}] device_ext={}",
            decision == UpscalerPreregistration.Decision.REGISTER_REQUESTED ? "requested" : "preregistered", ID, ID_FP16,
            ID_AMD_MARKER, ID_AMD_COHERENT, BASE_EXTENSIONS);
    }
}
