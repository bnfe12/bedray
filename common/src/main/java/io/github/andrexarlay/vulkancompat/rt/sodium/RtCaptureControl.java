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
import io.github.andrexarlay.vulkancompat.shaderhost.IrisShaderHost;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostKind;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHostSelector;
import io.github.andrexarlay.vulkancompat.shaderhost.ShaderHosts;
import io.github.andrexarlay.vulkancompat.shaderhost.iris.IrisPassGraph;
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

    private static boolean lastCaptureOn = false;
    private static RtActivation.Reason lastReason = null;
    private static boolean lastIrisOn = false;
    private static boolean first = true;
    private static long rebuildRequests;
    private static long rebuildScheduledSections;

    private RtCaptureControl() {
    }

    public static void onFrameBegin() {
        RtActivation.Reason reason = currentReason();
        boolean rtOn = reason == null;
        RtGeometryGate.publish(rtOn, reason);
        // The Iris raster host draws gbuffers/shadow from the same captured geometry, so capture runs for it even with RT
        // off. Native rays are suppressed separately (RtFrameRenderer.render returns early while iris is active).
        boolean irisOn = irisWantsCapture();
        RtGeometryGate.publishIris(irisOn);
        boolean captureOn = rtOn || irisOn;
        if (!first && captureOn == lastCaptureOn && reason == lastReason && irisOn == lastIrisOn) {
            return;
        }
        boolean wasOn = lastCaptureOn;
        first = false;
        lastCaptureOn = captureOn;
        lastReason = reason;
        lastIrisOn = irisOn;
        if (captureOn && !wasOn) {
            int scheduled = scheduleFullRebuild();
            LOGGER.info("[VKB-RT] [VKB-AUTOTEST] capture=on rebuild_scheduled_box={} rt={} iris={} (all built Sodium sections are re-meshed and captured)",
                scheduled < 0 ? "no_world" : String.valueOf(scheduled), rtOn, irisOn);
        } else if (!captureOn && wasOn) {
            int dropped = SectionGeometryRegistry.INSTANCE.clear();
            LOGGER.info("[VKB-RT] [VKB-AUTOTEST] capture=off reason={} ({}) dropped_sections={}",
                reason == null ? "none" : reason.name().toLowerCase(java.util.Locale.ROOT),
                reason == null ? "" : reason.text(), dropped);
        } else {
            LOGGER.info("[VKB-RT] [VKB-AUTOTEST] capture={} rt={} iris={} reason={}{}", captureOn ? "on" : "off", rtOn, irisOn,
                reason == null ? "none" : reason.name().toLowerCase(java.util.Locale.ROOT),
                reason == null ? "" : " (" + reason.text() + ")");
        }
    }

    /**
     * True when the Iris raster host needs geometry capture this frame: it is the active host, not disabled for the session,
     * and its full raster graph draws at least one geometry pass (shadow/gbuffers). A composite-only pack (no geometry pass)
     * does not capture, so the I3.5 post path keeps its zero-capture cost.
     */
    private static boolean irisWantsCapture() {
        if (ShaderHosts.active() != ShaderHostKind.IRIS) {
            return false;
        }
        ShaderHostSelector selector = ShaderHosts.selector();
        if (selector != null && selector.isDisabledForSession(ShaderHostKind.IRIS)) {
            return false;
        }
        IrisShaderHost host = ShaderHosts.iris();
        if (host == null) {
            return false;
        }
        IrisPassGraph.FullResult full = host.activeFullGraph();
        if (full == null) {
            return false;
        }
        for (IrisPassGraph.FullPass p : full.passes()) {
            if (!p.fullscreen()) {
                return true;
            }
        }
        return false;
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
