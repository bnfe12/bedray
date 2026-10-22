package io.github.andrexarlay.vulkancompat.mixin.compute;

import io.github.andrexarlay.vulkancompat.compute.ComputeAtlasBridge;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retires borrowed descriptors before the block atlas replaces or closes its native resources. */
@Mixin(value = TextureAtlas.class, remap = false)
public abstract class MixinComputeAtlas {
    @Inject(method = "releaseTextures()V", at = @At("HEAD"))
    private void bedray$releaseBorrowedAtlas(CallbackInfo ci) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(((TextureAtlas) (Object) this).location()))
            ComputeAtlasBridge.onAtlasReleased();
    }

    @Inject(method = "upload(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;)V", at = @At("RETURN"))
    private void bedray$publishBorrowedAtlas(CallbackInfo ci) {
        if (TextureAtlas.LOCATION_BLOCKS.equals(((TextureAtlas) (Object) this).location()))
            ComputeAtlasBridge.onAtlasUploaded();
    }
}
