package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import io.github.andrexarlay.vulkancompat.rt.particles.RtWeatherLayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.client.renderer.state.level.WeatherRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records what the game is about to draw for the weather columns of this frame (vertex / index state, rain and snow textures,
 * transform slice, lightmap) so that {@link RtWeatherLayer} can draw them again inside the particle layer of
 * {@link io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer}. Nothing is changed or cancelled: the vanilla draw into
 * the world target stays (it is the raster fallback and is replaced by the RT image when RT is displayed).
 * <p>
 * Signatures and fields: javap -p -c on {@code WeatherEffectRenderer}, mc 26.3 client jar. Both transparency paths funnel into the
 * private {@code render(WeatherRenderState, RenderPass, RenderPipeline)} - the classic one
 * ({@code LevelRenderer.executeClassicTransparency} -&gt; {@code render(state, pass)}) uses {@code RenderPipelines.WEATHER}, the
 * improved-transparency one ({@code LevelRenderer.executeOit} -&gt; {@code renderOit(stage, state, pass)}) uses
 * {@code RenderPipelines.OIT_WEATHER} per stage - so this is the single point where the frame's columns are visible, and the reason
 * the layer keeps only its first visit.
 * <p>
 * The vanilla call writes its own transform slice with {@code RenderSystem.getDynamicUniforms().writeTransform(modelViewMatrix)};
 * injected code cannot read a local variable of the target, so the same call is made here, at the head of the very same method
 * (nothing changes the model view matrix in between). It costs one entry of the game's dynamic uniform ring buffer per frame.
 * <p>
 * A failure of the capture switches weather off for the rest of the session instead of escaping into the world pass.
 */
@Mixin(value = WeatherEffectRenderer.class, remap = false)
public abstract class MixinWeatherEffectRenderer {
    @Shadow
    private GpuBuffer vertexBuffer;

    @Shadow
    private PrimitiveTopology primitiveTopology;

    @Shadow
    private AbstractTexture rainTexture;

    @Shadow
    private AbstractTexture snowTexture;

    @Shadow
    private GameRenderer gameRenderer;

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/level/WeatherRenderState;"
        + "Lcom/mojang/renderpearl/api/commands/RenderPass;"
        + "Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;)V", at = @At("HEAD"))
    private void vkb$captureWeatherColumns(WeatherRenderState state, RenderPass pass, RenderPipeline pipeline, CallbackInfo ci) {
        if (!RtWeatherLayer.capturing()) {
            return;
        }
        try {
            RtWeatherLayer.capture(vertexBuffer, primitiveTopology, rainTexture, state.rainColumns.size(), snowTexture,
                state.snowColumns.size(), RenderSystem.getDynamicUniforms().writeTransform(RenderSystem.getModelViewMatrixCopy()),
                gameRenderer.lightmap());
        } catch (RuntimeException | LinkageError e) {
            RtWeatherLayer.captureFailed(e);
        }
    }
}
