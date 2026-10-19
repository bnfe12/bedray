package io.github.andrexarlay.vulkancompat.rt.material;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.Map;

/**
 * Read access to the private {@code TextureAtlas.texturesByName} map (javap of libs/minecraft-26.3-client.jar:
 * {@code private java.util.Map<Identifier, TextureAtlasSprite> texturesByName}), implemented by the accessor mixin
 * {@code mixin.rtx.MixinTextureAtlasAccessor}. Used only to bake the RT material atlas.
 */
public interface AtlasSpritesAccess {
    Map<Identifier, TextureAtlasSprite> vkb$texturesByName();
}
