package io.github.andrexarlay.vulkancompat.rt.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import io.github.andrexarlay.vulkancompat.rt.material.RtMaterialAtlas;
import io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static io.github.andrexarlay.vulkancompat.rt.world.RtDenoiserLifecycle.dlssRrDenoiser;
import static io.github.andrexarlay.vulkancompat.rt.world.RtDenoiserLifecycle.frameColorTemporallyResolved;

/**
 * The part of the RT frame that runs after the denoiser: HDR export for bloom, the RR output resolve, local tone mapping,
 * the raster particle composite, the atmosphere pass and the copy of the presented image into the engine target. It borrows
 * the frame's owners as arguments and keeps no state of its own.
 */
final class RtFramePresentation {
    private RtFramePresentation() {
    }

    /** Everything the presentation steps read; the renderer fills it once per frame from its own owners. */
    record Inputs(VkCommandBuffer cmd, VulkanDevice vulkanDevice, RenderTarget target, RtFrameParams params, RtPass pass,
                  RtFrameResources frameResources, RtOutputResolve outputResolve, RtLocalTonemap localTonemap,
                  RtAtmosphere atmosphere, org.joml.Matrix4f curViewProj, RtWorld world, RtMaterialAtlas materials,
                  long atlasView, long lightmapView, org.joml.Vector3f bobOrigin, RtGpuTimer gpuTimer,
                  int w, int h, boolean rrColorResolved) {
    }

    /** Records the presentation steps; returns true when the presented image was copied into the engine target. */
    static boolean record(Inputs in) {
        VkCommandBuffer cmd = in.cmd();
        VulkanDevice vulkanDevice = in.vulkanDevice();
        RenderTarget target = in.target();
        RtFrameParams params = in.params();
        RtPass pass = in.pass();
        RtFrameResources frameResources = in.frameResources();
        RtOutputResolve outputResolve = in.outputResolve();
        RtLocalTonemap localTonemap = in.localTonemap();
        RtAtmosphere atmosphere = in.atmosphere();
        org.joml.Matrix4f curViewProj = in.curViewProj();
        RtWorld world = in.world();
        RtMaterialAtlas materials = in.materials();
        long atlasView = in.atlasView();
        long lightmapView = in.lightmapView();
        org.joml.Vector3f bobOrigin = in.bobOrigin();
        RtGpuTimer gpuTimer = in.gpuTimer();
        int w = in.w();
        int h = in.h();
        boolean rrColorResolved = in.rrColorResolved();
        boolean displayed = false;
        RtMemory.Img presented = frameResources.output(), presentedDepth = frameResources.depth();
        RtAtmosphere.SunGlare sunGlare = atmosphere.projectSunGlare(params, curViewProj);
        // The HDR export is only useful for a tone-mapped raw frame with active bloom or glare.
        boolean rawHdrExported = !frameColorTemporallyResolved && params.tonemap && params.mode == RtConfig.MODE_LIT
            && (RtAtmosphereSettings.bloom() || sunGlare.active())
            && frameResources.postHdr().width == presented.width && frameResources.postHdr().height == presented.height
            && pass.exportHdr(cmd, frameResources.postHdr().view, w, h);
        if (rawHdrExported) VkBarriers.fullMemoryBarrier(cmd);
        if (WorldTargetManager.rrResolvesOutput()) {
            RtComputeCheckpoints.mark(vulkanDevice, cmd, "output_resolve");
            outputResolve.record(cmd, params, frameResources, rrColorResolved);
            VkBarriers.fullMemoryBarrier(cmd);
            presented = frameResources.composite();
            presentedDepth = frameResources.displayDepth();
        }
        // Local tone mapping replaces the RR compose's global curve with a local one (RtLocalTonemap). Only on a frame
        // whose colour the denoiser resolved: the raw fallback has no HDR source to rebuild from.
        if (rrColorResolved && localTonemap != null && RtLocalTonemap.enabled() && dlssRrDenoiser != null
                && dlssRrDenoiser.outDiffuse() != null && frameResources.localLum() != null
                && dlssRrDenoiser.outDiffuse().width == presented.width && dlssRrDenoiser.outDiffuse().height == presented.height) {
            RtComputeCheckpoints.mark(vulkanDevice, cmd, "local_tonemap");
            localTonemap.record(cmd, dlssRrDenoiser.outDiffuse(), presented, frameResources.localLum(), presentedDepth,
                params.exposure, params.frame);
            VkBarriers.fullMemoryBarrier(cmd);
        }
        // Raster particles/weather and world depth match the presentation grid and stay outside RR.
        RtComputeCheckpoints.mark(vulkanDevice, cmd, "particles_composite");
        io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer.composite(cmd, RtFrameRenderer.pathSlangRuntime(vulkanDevice),
            presented.view, presented.width, presented.height);

        // god rays + bloom + sun glare on the denoised final image (reads the ray distance, writes only rt_output)
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, RtFrameRenderer.SPAN_ATMO, false);
        }
        RtComputeCheckpoints.mark(vulkanDevice, cmd, "atmosphere");
        // The bloom reads the denoiser's HDR resolve when it has the presented extent (DLSS RR at its output size).
        boolean hdrBloom = rrColorResolved && dlssRrDenoiser != null && dlssRrDenoiser.outDiffuse() != null
            && dlssRrDenoiser.outDiffuse().width == presented.width && dlssRrDenoiser.outDiffuse().height == presented.height;
        RtMemory.Img postHdr = hdrBloom ? dlssRrDenoiser.outDiffuse() : !frameColorTemporallyResolved && rawHdrExported
            && frameResources.postHdr().width == presented.width && frameResources.postHdr().height == presented.height
            ? frameResources.postHdr() : null;
        atmosphere.record(cmd, vulkanDevice, params, sunGlare, postHdr != null ? postHdr.view : 0L,
            new RtFrameBindings.Views(world.tlasHandle(), world.sectionTable(), presented.view, atlasView, materials.materialView(),
                materials.normalView(), 0L, null, presentedDepth.view, lightmapView),
            presented.width, presented.height, bobOrigin.x, bobOrigin.y, bobOrigin.z);
        if (gpuTimer != null) {
            gpuTimer.stamp(cmd, RtFrameRenderer.SPAN_ATMO, true);
        }

        if (RtConfig.displayed()) {
            if (presented.width != target.width || presented.height != target.height)
                throw new RtFailure("copy_to_target", "presentation extent does not match the engine target");
            long dst = WorldTargetManager.extractVkImage(target);
            if (dst == 0L) {
                throw new RtFailure("copy_to_target", "engine VkImage of the main/world target is 0");
            }
            RtComputeCheckpoints.mark(vulkanDevice, cmd, "copy_to_target");
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.get(0).extent().width(presented.width).height(presented.height).depth(1);
                VK10.vkCmdCopyImage(cmd, presented.image, VK10.VK_IMAGE_LAYOUT_GENERAL, dst, VK10.VK_IMAGE_LAYOUT_GENERAL, region);
            }
            displayed = true;
        }
        return displayed;
    }
}
