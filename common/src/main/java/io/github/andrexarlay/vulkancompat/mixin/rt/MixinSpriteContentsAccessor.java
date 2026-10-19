package io.github.andrexarlay.vulkancompat.mixin.rt;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** {@code SpriteContents.originalImage} (javap -p, 26.3 client jar: private final NativeImage originalImage). */
@Mixin(value = SpriteContents.class, remap = false)
public interface MixinSpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage vkb$originalImage();
}
