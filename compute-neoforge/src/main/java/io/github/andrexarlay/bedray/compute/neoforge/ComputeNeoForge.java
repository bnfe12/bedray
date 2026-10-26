package io.github.andrexarlay.bedray.compute.neoforge;

import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.bedray.compute.VoxelComputeBackend;
import net.neoforged.fml.common.Mod;
import net.neoforged.api.distmarker.Dist;

@Mod(value = "bedray_compute", dist = Dist.CLIENT)
public final class ComputeNeoForge {
    public ComputeNeoForge() {
        GraphicsBackendRegistry.register(new VoxelComputeBackend());
    }
}
