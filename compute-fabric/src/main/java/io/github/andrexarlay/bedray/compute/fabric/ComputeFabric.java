package io.github.andrexarlay.bedray.compute.fabric;

import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import io.github.andrexarlay.bedray.compute.VoxelComputeBackend;
import net.fabricmc.api.ClientModInitializer;

public final class ComputeFabric implements ClientModInitializer {
    @Override public void onInitializeClient() {
        GraphicsBackendRegistry.register(new VoxelComputeBackend());
    }
}
