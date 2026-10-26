package io.github.andrexarlay.vulkancompat.shaderhost;

import io.github.andrexarlay.vulkanbridge.api.DeviceFeatureRegistry;
import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.shaderhost.customrt.CustomRtStage;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtDeviceRequirements;
import io.github.andrexarlay.vulkancompat.shaderhost.dirtrt.DirtRtRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * The one call that makes the shader hosts reachable. Before this existed {@link ShaderHosts#bootstrap} had no caller,
 * so {@link ShaderHosts#active()} was always NATIVE and neither the Iris post host nor the Dirt RT path could run, whatever
 * was configured. Runs from {@code RtxBootstrap}'s early hook, before the device exists (no Minecraft class touched):
 * <ol>
 *   <li>selects the shader host from {@code shader_host} / {@code -Dvkrtx.shader_host} (probes and compiles the Iris pack);</li>
 *   <li>detects a ray-tracing-pipeline pack (Dirt RT) and, when opted in, compiles it and requests its device group;</li>
 *   <li>loads the opt-in custom ray-query compute stage, whose device needs are covered by the hardware RT group.</li>
 * </ol>
 * A failure here is logged and leaves the native renderer untouched; it never aborts game start.
 */
public final class ShaderHostStartup {
    private static final Logger LOGGER = LoggerFactory.getLogger("VulkanRtx/ShaderHost");

    private ShaderHostStartup() {}

    public static void run(Path gameDir, DeviceFeatureRegistry registry) {
        if (gameDir == null) {
            LOGGER.warn("[VKB-SHADERHOST] no game directory; shader hosts stay native");
            return;
        }
        try {
            ShaderHosts.bootstrap(gameDir);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-SHADERHOST] bootstrap failed; native renderer retained\n{}", LogPaths.stack(e));
        }
        try {
            DirtRtRuntime.bootstrap(gameDir);
            if (DirtRtRuntime.hasProgram()) {
                registry.addLateProvider(DirtRtDeviceRequirements.LATE_PROVIDER_ID, DirtRtRuntime::registerDeviceRequirement);
            }
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-DIRTRT] bootstrap failed\n{}", LogPaths.stack(e));
        }
        try {
            CustomRtStage.bootstrap(gameDir);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.error("[VKB-CUSTOMRT] bootstrap failed\n{}", LogPaths.stack(e));
        }
    }
}
