package io.github.andrexarlay.vulkancompat.mixin.rtx;

import io.github.andrexarlay.vulkancompat.rt.material.AtlasSpritesAccess;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** Accessor of {@code TextureAtlas.texturesByName} for the RT material atlas bake (field verified with javap -p). */
@Mixin(value = TextureAtlas.class, remap = false)
public interface MixinTextureAtlasAccessor extends AtlasSpritesAccess {

    @Accessor("texturesByName")
    @Override
    Map<Identifier, TextureAtlasSprite> vkb$texturesByName();
}
