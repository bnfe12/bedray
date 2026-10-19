package io.github.andrexarlay.vulkancompat.mixin.rt;

import io.github.andrexarlay.vulkancompat.rt.particles.ParticleGroupAccess;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The prepared group record of {@code QuadParticleFeatureRenderer} is package-private; this exposes its three fields. */
@Mixin(targets = "net.minecraft.client.renderer.feature.QuadParticleFeatureRenderer$PreparedGroup", remap = false)
public interface MixinParticleGroupAccessor extends ParticleGroupAccess {
    @Accessor("layers")
    @Override
    Map<SingleQuadParticle.Layer, StagedVertexBuffer.Draw> vkb$layers();

    @Accessor("textures")
    @Override
    Map<SingleQuadParticle.Layer, AbstractTexture> vkb$textures();

    @Accessor("translucent")
    @Override
    boolean vkb$translucent();
}
