package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Optional;

/**
 * Built-in bilinear upscaler backend using Minecraft's {@link RenderPipelines#TRACY_BLIT}.
 * Always available as the primary baseline and transparent fallback.
 */
public class BilinearBackend implements IUpscalerBackend {

    private volatile boolean initialized = true;

    @Override
    public boolean init(int inW, int inH, int outW, int outH) {
        this.initialized = true;
        return true;
    }

    @Override
    public boolean process(RenderTarget input, RenderTarget output) {
        if (input == null || output == null) {
            return false;
        }
        GpuTextureView srcView = input.getColorTextureView();
        GpuTextureView dstView = output.getColorTextureView();
        if (srcView == null || dstView == null) {
            return false;
        }

        CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
        try (RenderPass p = enc.createRenderPass(() -> "VKB upscale bilinear", dstView, Optional.empty())) {
            p.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
            RenderSystem.bindDefaultUniforms(p);
            p.setUniform("InSampler", srcView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            p.draw(3, 1, 0, 0);
        }
        return true;
    }

    @Override
    public void cleanup() {
        // No external resources to release
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean isInitialized() {
        return initialized;
    }
}
