package io.github.andrexarlay.vulkancompat.mixin.rt;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** {@code TextureAtlas.sprites / width / height} (javap -p, 26.3 client jar: private List sprites, private int width, height). */
@Mixin(value = TextureAtlas.class, remap = false)
public interface MixinTextureAtlasAccessor {
    @Accessor("sprites")
    List<TextureAtlasSprite> vkb$sprites();

    @Accessor("width")
    int vkb$width();

    @Accessor("height")
    int vkb$height();
}
