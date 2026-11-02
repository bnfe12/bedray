package io.github.andrexarlay.vulkancompat.rt.world;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Device identity plus address prevents closed-cache resurrection and permits genuine VkDevice address reuse. */
final class PipelineCacheDevices<D> {
    private record Scope<D>(WeakReference<D> device, PipelineCacheOwner owner) {}
    private final Map<Long, Scope<D>> devices = new HashMap<>();

    synchronized PipelineCacheOwner forUse(D device, long address, Consumer<PipelineCacheOwner> initialize) {
        Objects.requireNonNull(device);
        devices.values().removeIf(scope -> scope.owner.isClosed() && scope.device.get() == null);
        Scope<D> scope = devices.get(address);
        if (scope != null && scope.device.get() == device) return scope.owner;
        if (scope != null && !scope.owner.isClosed())
            throw new IllegalStateException("pipeline cache device identity changed before retirement; device=0x" + Long.toHexString(address));
        var owner = new PipelineCacheOwner(0L, null);
        devices.put(address, new Scope<>(new WeakReference<>(device), owner));
        // All bookkeeping allocations precede the first native cache allocation.
        try { initialize.accept(owner); }
        catch (RuntimeException | Error failure) { owner.initializationFailed(); throw failure; }
        return owner;
    }

    /** Creates a closed-device tombstone even when no pipeline has requested a cache yet. */
    synchronized PipelineCacheOwner forClose(D device, long address) {
        return forUse(device, address, owner -> {});
    }
}
