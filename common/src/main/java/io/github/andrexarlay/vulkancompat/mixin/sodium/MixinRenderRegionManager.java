package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.rt.sodium.SectionCapture;
import io.github.andrexarlay.vulkancompat.compute.ComputeRenderBridge;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * H3 (render thread): the public {@code uploadResults(Collection, UniformBufferManager)} receives exactly the build outputs
 * Sodium accepted; they are destroyed only after this call returns (RenderSectionManager.processChunkBuilds).
 * Signature: javap on libs/sodium-core.jar, source RenderRegionManager.java:66. The private overload with a RenderRegion
 * is deliberately not targeted.
 */
@Mixin(value = RenderRegionManager.class, remap = false)
public abstract class MixinRenderRegionManager {

    @Inject(method = "uploadResults(Ljava/util/Collection;"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/UniformBufferManager;)V",
            at = @At("HEAD"))
    private void vkb$acceptGeometry(Collection<BuilderTaskOutput> results, UniformBufferManager uniforms, CallbackInfo ci) {
        // Voxel invalidation must remain independent of the hardware RT capture gate.
        for (BuilderTaskOutput result : results) {
            if (result instanceof ChunkBuildOutput output && !output.section.isDisposed()) {
                var section = output.section;
                ComputeRenderBridge.sectionChanged(section.getChunkX(), section.getChunkY(), section.getChunkZ());
            }
        }
        SectionCapture.acceptOnRenderThread(results);
    }
}
