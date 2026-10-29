package io.github.andrexarlay.vulkancompat.rt.slang;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

/** One native core per actual host VulkanDevice, shared across renderer feature lifetimes. */
public final class SlangRuntimePool {
    private static final IdentityLeasePool<VulkanDevice, SlangRuntime> POOL = new IdentityLeasePool<>(
        device -> SlangRuntime.create(device, SlangDeviceCapabilities.enabledMask(device)),
        runtime -> {
            runtime.close();
            if (!runtime.isClosed()) throw new IllegalStateException("native Slang runtime close refused; lease retained for retry");
        });
    private SlangRuntimePool() {}
    public static Lease acquire(VulkanDevice device) { return new Lease(POOL.acquire(device)); }
    public static final class Lease implements AutoCloseable {
        private final IdentityLeasePool<VulkanDevice, SlangRuntime>.Lease delegate;
        private Lease(IdentityLeasePool<VulkanDevice, SlangRuntime>.Lease delegate) { this.delegate = delegate; }
        public SlangRuntime runtime() { return delegate.resource(); }
        @Override public void close() { delegate.close(); }
    }
}
