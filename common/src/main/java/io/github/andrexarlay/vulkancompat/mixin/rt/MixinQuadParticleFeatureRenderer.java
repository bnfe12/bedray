package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import io.github.andrexarlay.vulkancompat.rt.particles.ParticleGroupAccess;
import io.github.andrexarlay.vulkancompat.rt.particles.RtParticleLayer;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Records what the game is about to draw for a particle group (pipeline, vertex / index buffer slices, atlas texture, transform
 * slice, lightmap) so that {@link RtParticleLayer} can draw the same particles again into its own layer after the world pass.
 * Nothing is changed or cancelled: the vanilla draw into the world target stays (it is the raster fallback and is replaced by the
 * RT image when RT is displayed). Signature and fields: javap -p -c on {@code QuadParticleFeatureRenderer}, 26.3 client jar
 * ({@code executeGroup(FeatureFrameContext, OitStage, RenderPass, int, List, boolean)}, field {@code groups},
 * field {@code dynamicTransforms}). The staged vertex buffer forgets its draws in PreparedFrame.close (endDraw), so the
 * ExecuteInfo of a draw can only be taken while the group executes.
 */
@Mixin(value = QuadParticleFeatureRenderer.class, remap = false)
public abstract class MixinQuadParticleFeatureRenderer {
    @Shadow
    @SuppressWarnings("rawtypes")
    private List groups;

    @Shadow
    private GpuBufferSlice dynamicTransforms;

    @Inject(method = "executeGroup", at = @At("HEAD"))
    private void vkb$captureParticleGroup(FeatureFrameContext ctx, OitStage stage, RenderPass pass, int groupIndex,
                                          List<?> submits, boolean strictlyOrdered, CallbackInfo ci) {
        if (!RtParticleLayer.capturing() || groupIndex < 0 || groupIndex >= groups.size()) {
            return;
        }
        RtParticleLayer.capture(ctx, dynamicTransforms, groupIndex, (ParticleGroupAccess) groups.get(groupIndex));
    }
}
