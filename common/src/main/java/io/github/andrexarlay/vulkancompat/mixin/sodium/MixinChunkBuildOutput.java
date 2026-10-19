package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.rt.sodium.DecodedSection;
import io.github.andrexarlay.vulkancompat.rt.sodium.RtGeometryHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H2: carries the geometry decoded on the worker thread (H1) to the render thread (H3) without a global map.
 * {@code destroy()} (public void, ChunkBuildOutput.java:38, javap on libs/sodium-core.jar) is called for every result,
 * accepted or not, so the reference is dropped there and a rejected result does not keep its decoded copy alive.
 */
@Mixin(value = ChunkBuildOutput.class, remap = false)
public abstract class MixinChunkBuildOutput implements RtGeometryHolder {

    @Unique
    private volatile DecodedSection vkb$geometry;

    @Override
    public DecodedSection vkb$getGeometry() {
        return this.vkb$geometry;
    }

    @Override
    public void vkb$setGeometry(DecodedSection geometry) {
        this.vkb$geometry = geometry;
    }

    @Inject(method = "destroy()V", at = @At("HEAD"))
    private void vkb$releaseGeometry(CallbackInfo ci) {
        this.vkb$geometry = null;
    }
}
