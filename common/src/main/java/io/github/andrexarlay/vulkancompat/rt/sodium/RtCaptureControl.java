package io.github.andrexarlay.vulkancompat.rt.sodium;

import io.github.andrexarlay.vulkanbridge.api.VulkanBridge;
import io.github.andrexarlay.vulkancompat.rtx.config.VulkanRtxConfig;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.RtActivation;
import io.github.andrexarlay.vulkancompat.rtx.raytracing.VulkanCapabilityChecker;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalUpscaler;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerBackend;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerRequest;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessRuntime;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides at the head of every frame (render thread) whether the world RT path runs ({@link RtActivation}) and handles the
 * transitions of the Sodium geometry capture:
 * <ul>
 *   <li>off -> on: Sodium only hands us geometry when it builds a section, so every already built section around the camera
 *   is scheduled for a rebuild ({@code SodiumWorldRenderer.scheduleRebuildForChunks(minX,minY,minZ,maxX,maxY,maxZ,boolean)},
 *   which calls {@code RenderSectionManager.scheduleRebuild} for built sections only; javap + vineflower of
 *   libs/sodium-core.jar). The capture mixins then decode the new outputs.</li>
 *   <li>on -> off: the decoded CPU copies are dropped ({@link SectionGeometryRegistry#clear}); the GPU side is released by
 *   {@code RtFrameRenderer.render} (gate off -> shutdown) later in the same frame.</li>
 * </ul>
 * Both transitions are logged as {@code [VKB-RT] capture=on|off ...}.
 */
public final class RtCaptureControl {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");

    private static boolean lastOn = false;
    private static RtActivation.Reason lastReason = null;
    private static boolean first = true;
    private static long rebuildRequests;
    private static long rebuildScheduledSections;

    private RtCaptureControl() {
    }

    public static void onFrameBegin() {
        RtActivation.Reason reason = currentReason();
        boolean on = reason == null;
        RtGeometryGate.publish(on, reason);
        if (!first && on == lastOn && reason == lastReason) {
            return;
        }
        boolean wasOn = lastOn;
        first = false;
        lastOn = on;
        lastReason = reason;
        if (on && !wasOn) {
            int scheduled = scheduleFullRebuild();
            LOGGER.info("[VKB-RT] [VKB-AUTOTEST] capture=on rebuild_scheduled_box={} (all built Sodium sections are re-meshed and captured)",
                scheduled < 0 ? "no_world" : String.valueOf(scheduled));
        } else if (!on && wasOn) {
            int dropped = SectionGeometryRegistry.INSTANCE.clear();
            LOGGER.info("[VKB-RT] [VKB-AUTOTEST] capture=off reason={} ({}) dropped_sections={}", reason.name().toLowerCase(java.util.Locale.ROOT),
                reason.text(), dropped);
        } else {
            LOGGER.info("[VKB-RT] [VKB-AUTOTEST] capture={} reason={}{}", on ? "on" : "off",
                reason == null ? "none" : reason.name().toLowerCase(java.util.Locale.ROOT),
                reason == null ? "" : " (" + reason.text() + ")");
        }
    }

    private static RtActivation.Reason currentReason() {
        boolean vulkan = VulkanBridge.isVulkan();
        boolean config = configWantsRt();
        // evaluated only when it matters: the capability check caches its first answer
        boolean supported = vulkan && config && VulkanCapabilityChecker.isHardwareRayTracingSupported();
        UpscalerBackend requested = UpscalerRequest.current().backend();
        boolean requestedFailed = switch (requested) {
            case DLSS -> DlssRuntime.state() == DlssRuntime.State.FAILED;
            case XESS -> XessRuntime.state() == XessRuntime.State.FAILED;
            default -> false;
        };
        return RtActivation.blockReason(vulkan, config, supported, RtGeometryGate.failure() != null, requested, requestedFailed,
            TemporalUpscaler.isActive());
    }

    /**
     * The user's RT choice: an RtMode other than OFF (Ray Tracing or Path Tracing, {@code VulkanRtxConfig.effectiveRtMode()}).
     * The only place that reads the config for the gate.
     */
    static boolean configWantsRt() {
        return VulkanRtxConfig.effectiveRtMode().isActive();
    }

    /**
     * Schedules a rebuild of every built section within the render distance (+2) around the camera entity.
     *
     * @return number of section positions in the box, or -1 when no world / Sodium renderer exists (nothing is built then,
     * and sections built from now on are captured anyway)
     */
    private static int scheduleFullRebuild() {
        Minecraft mc = Minecraft.getInstance();
        SodiumWorldRenderer sodium = SodiumWorldRenderer.instanceNullable();
        Entity cam = mc != null ? mc.getCameraEntity() : null;
        if (sodium == null || mc.level == null || cam == null) {
            return -1;
        }
        int r = mc.options.getEffectiveRenderDistance() + 2;
        int cx = SectionPos.blockToSectionCoord(cam.getX());
        int cz = SectionPos.blockToSectionCoord(cam.getZ());
        int minY = mc.level.getMinSectionY();
        int maxY = mc.level.getMaxSectionY();
        sodium.scheduleRebuildForChunks(cx - r, minY, cz - r, cx + r, maxY, cz + r, false);
        rebuildRequests++;
        int box = (2 * r + 1) * (2 * r + 1) * (maxY - minY + 1);
        rebuildScheduledSections += box;
        return box;
    }

    public static long rebuildRequests() {
        return rebuildRequests;
    }
}
