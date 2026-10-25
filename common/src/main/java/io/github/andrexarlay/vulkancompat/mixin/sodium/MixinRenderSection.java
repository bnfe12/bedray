package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.rt.sodium.SectionCapture;
import io.github.andrexarlay.vulkancompat.compute.ComputeRenderBridge;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * H4 (render thread): {@code public void delete()} (RenderSection.java:123, javap on libs/sodium-core.jar) is called when a
 * section leaves the world or the renderer is destroyed (QueuedSectionStorage.deleteAll); the geometry is dropped.
 */
@Mixin(value = RenderSection.class, remap = false)
public abstract class MixinRenderSection {

    @Inject(method = "delete()V", at = @At("HEAD"))
    private void vkb$dropGeometry(CallbackInfo ci) {
        var section = (RenderSection) (Object) this;
        ComputeRenderBridge.sectionRemoved(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        SectionCapture.onSectionDeleted(section);
    }
}
