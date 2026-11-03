package io.github.andrexarlay.vulkancompat.rt.world;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Device identity plus address prevents closed-cache resurrection and permits genuine VkDevice address reuse. */
final class PipelineCacheDevices<D> {
    private record Scope<D>(WeakReference<D> device, long address, PipelineCacheOwner owner) {}
    // Device creation is rare. Retain every reachable identity, including old generations of the same address.
    private final List<Scope<D>> devices = new ArrayList<>();

    synchronized PipelineCacheOwner forUse(D device, long address, Consumer<PipelineCacheOwner> initialize) {
        Objects.requireNonNull(device);
        devices.removeIf(scope -> scope.owner.isClosed() && scope.device.get() == null);
        for (Scope<D> scope : devices) {
            if (scope.device.get() == device) {
                if (scope.address != address) throw new IllegalStateException("pipeline cache device address changed");
                return scope.owner;
            }
        }
        for (Scope<D> scope : devices) {
            if (scope.address == address && !scope.owner.isClosed())
                throw new IllegalStateException("pipeline cache device identity changed before retirement; device=0x" + Long.toHexString(address));
        }
        var owner = new PipelineCacheOwner(0L, null);
        devices.add(new Scope<>(new WeakReference<>(device), address, owner));
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
