package io.github.andrexarlay.vulkancompat.mixin.sodium;

import io.github.andrexarlay.vulkancompat.rt.sodium.SectionCapture;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * H1 (Hardware RT geometry capture): after a Sodium worker finished a section mesh, decode its vertices.
 * Signature: {@code public ChunkBuildOutput execute(ChunkBuildContext, CancellationToken)}, javap on libs/sodium-core.jar
 * (Bedray Core overload returning BuilderTaskOutput is a different descriptor); source ChunkBuilderMeshingTask.java:74.
 * The method returns null when the task was cancelled; {@link SectionCapture#captureOnWorker} handles null.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class MixinChunkBuilderMeshingTask {

    @Inject(method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
            + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
            + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
            at = @At("RETURN"))
    private void vkb$captureGeometry(ChunkBuildContext buildContext, CancellationToken cancellationToken,
                                     CallbackInfoReturnable<ChunkBuildOutput> cir) {
        SectionCapture.captureOnWorker(cir.getReturnValue());
    }
}
