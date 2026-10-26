package io.github.andrexarlay.vulkancompat.rtx.upscaler.xess;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.api.DeviceRequirement;
import io.github.andrexarlay.vulkanbridge.api.FeatureRequest;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerPreregistration;
import org.lwjgl.vulkan.VK13;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Intel XeSS device requirements (XeSS integration notes), formerly hard-coded in MixinVulkanFeatureSets and
 * MixinVulkanInstance. Registered when XeSS is requested before the device exists ({@code -Dvkbridge.xess=<mode>} or the
 * toml) or, by default, whenever the library exists (preregistration so that XeSS can be selected at runtime, see
 * {@link UpscalerPreregistration}; {@code -Dvkrtx.upscaler.preregister=false} restores request-only registration). The groups
 * are enabled only when {@code bedray/natives/libxess.dll} exists (the condition), otherwise the device is created
 * exactly as without XeSS.
 * <p>
 * Two groups so the optional speed features do not veto the mandatory ones (the engine silently drops an unsupported
 * optional set; the log line "Enabling optional FeatureSet [..]" proves which one was kept):
 * <ul>
 * <li>"VKB XeSS": VK_EXT_mutable_descriptor_type + mutableDescriptorType, shaderStorageImageWriteWithoutFormat,
 *     shaderStorageImageExtendedFormats; also asks for instance apiVersion 1.3 (libxess.dll 2.0.2 minVkApiVersion=0x403000).</li>
 * <li>"VKB XeSS Perf": VK_KHR_shader_integer_dot_product, VK_KHR_shader_float16_int8, VK_EXT_scalar_block_layout
 *     (the three extension names found in libxess.dll) + shaderIntegerDotProduct, shaderInt8, scalarBlockLayout.</li>
 * </ul>
 * Struct/field names verified with javap on lwjgl-vulkan-3.4.1 and against decompiled VulkanFeatureSets / VulkanFeature.
 * This class must be called after the game directory is resolvable the way XessRuntime does it (see DeviceRequirementsBootstrap).
 */
public final class XessDeviceRequirements {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FeatureSets");

    public static final String ID = "VKB XeSS";
    public static final String ID_PERF = "VKB XeSS Perf";
    public static final Set<String> IDS = Set.of(ID, ID_PERF);

    private XessDeviceRequirements() {}

    /** Condition used for both groups: the library exists in the given game dir. */
    public static Supplier<Boolean> libraryPresent(Supplier<Path> gameDir) {
        return () -> XessConfig.libraryExists(gameDir.get());
    }

    public static DeviceRequirement base(Supplier<Boolean> condition) {
        return new DeviceRequirement(
            ID,
            Set.of("VK_EXT_mutable_descriptor_type"),
            List.of(
                FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceMutableDescriptorTypeFeaturesEXT.class, "mutableDescriptorType"),
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageWriteWithoutFormat"),
                // our velocity compute shader writes an rg16f storage image (capability StorageImageExtendedFormats)
                FeatureRequest.core(FeatureRequest.VK10, "shaderStorageImageExtendedFormats")
            ),
            VK13.VK_API_VERSION_1_3,
            true,
            condition
        );
    }

    public static DeviceRequirement perf(Supplier<Boolean> condition) {
        return new DeviceRequirement(
            ID_PERF,
            Set.of("VK_KHR_shader_integer_dot_product", "VK_KHR_shader_float16_int8", "VK_EXT_scalar_block_layout"),
            List.of(
                FeatureRequest.of(org.lwjgl.vulkan.VkPhysicalDeviceShaderIntegerDotProductFeatures.class, "shaderIntegerDotProduct"),
                FeatureRequest.core(FeatureRequest.VK12, "shaderInt8"),
                FeatureRequest.core(FeatureRequest.VK12, "scalarBlockLayout")
            ),
            0,
            true,
            condition
        );
    }

    /**
     * Registers both groups when XeSS was requested, or (preregistration, {@link UpscalerPreregistration}) when its library is
     * present although XeSS is not selected, so that XeSS can be selected at runtime. The result is recorded in
     * {@link UpscalerPreregistration#record}.
     */
    public static void register(DeviceFeatureRegistry registry, Supplier<Path> gameDir) {
        register(registry, gameDir, XessConfig.requestedQuality() != null, UpscalerPreregistration.enabled());
    }

    /** Same as {@link #register(DeviceFeatureRegistry, Supplier)} with the two inputs of the decision given explicitly (tests). */
    public static void register(DeviceFeatureRegistry registry, Supplier<Path> gameDir, boolean requested, boolean preregisterEnabled) {
        UpscalerPreregistration.Decision decision = UpscalerPreregistration.decide(requested, preregisterEnabled);
        if (decision == UpscalerPreregistration.Decision.SKIP) {
            UpscalerPreregistration.record(UpscalerBackend.XESS, false);
            LOGGER.info("[VKB-AUTOTEST] upscaler_prereg backend=XESS registered=false reason=disabled (-D{}=false, XeSS not requested: selecting it needs a restart)",
                UpscalerPreregistration.PROPERTY);
            return;
        }
        Supplier<Boolean> condition = libraryPresent(gameDir);
        if (!condition.get()) {
            UpscalerPreregistration.record(UpscalerBackend.XESS, false);
            if (requested) {
                LOGGER.warn("[VKB-AUTOTEST] xess=FAILED stage=device_hook result=dll_missing path={} (XeSS FeatureSets NOT added, device unchanged)",
                    LogPaths.show(XessConfig.libraryPath(gameDir.get())));
            } else {
                LOGGER.info("[VKB-AUTOTEST] upscaler_prereg backend=XESS registered=false reason=dll_missing path={}",
                    LogPaths.show(XessConfig.libraryPath(gameDir.get())));
            }
            return;
        }
        registry.register(base(condition));
        registry.register(perf(condition));
        UpscalerPreregistration.record(UpscalerBackend.XESS, true);
        LOGGER.info("[VKB-AUTOTEST] upscaler_prereg backend=XESS registered=true reason={} instance_api=1.3 device_ext={}",
            decision == UpscalerPreregistration.Decision.REGISTER_REQUESTED ? "requested" : "preregistered",
            "VK_EXT_mutable_descriptor_type,VK_KHR_shader_integer_dot_product,VK_KHR_shader_float16_int8,VK_EXT_scalar_block_layout");
    }
}
