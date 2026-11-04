package io.github.andrexarlay.bedray.api.v1;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * API minor 7: borrowed, render-thread AOT compute service on the host device.
 * Programs and per-dispatch descriptors belong to the host native core. Providers
 * own their resources and barriers, but never compile shaders, submit or wait.
 * The host discards the entire command recording on false/exception and drains
 * prior work before closing the provider. No optional device feature is required.
 */
public interface AotComputeExecutor {
    /** Validates compute stage, zero optional capabilities, push size and local size before use. */
    Program prepare(String name, int pushBytes, int localX, int localY);

    interface Program {
        /** All resources must match the reflected names/types. Each dispatch gets immutable descriptors. */
        void dispatch(long commandBuffer, int groupsX, int groupsY, int groupsZ,
                      List<Resource> resources, ByteBuffer pushConstants);
    }

    /** Vulkan descriptor values without exposing the host's native implementation. */
    record Resource(String name, int descriptorType, int imageLayout, long handle,
                    long sampler, long offset, long range) {
        public static Resource storageBuffer(String name, long buffer, long bytes) {
            return new Resource(name, 7, 0, buffer, 0, 0, bytes);
        }
        public static Resource storageImage(String name, long view) {
            return new Resource(name, 3, 1, view, 0, 0, 0);
        }
        public static Resource sampled(String name, long view, long sampler, int layout) {
            return new Resource(name, 1, layout, view, sampler, 0, 0);
        }
    }
}
