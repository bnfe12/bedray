package io.github.andrexarlay.vulkancompat.rt.slang;

import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;

/**
 * Loads AOT Slang SPIR-V for hosts that own their Vulkan pipeline layout directly.
 *
 * <p>The new renderer's native core registers passes through {@link SlangProgram}. A few host-owned utility
 * pipelines (frame-generation preparation, Iris depth conversion, velocity reprojection, NRD packing) keep their
 * existing Vulkan layouts; they only need the compiled words. Loading them from the generated resource root keeps
 * those hosts on the same AOT contract and removes runtime GLSL compilation.
 */
public final class SlangAot {
    private SlangAot() {
    }

    /** Direct little-endian SPIR-V words for {@code name}, ready for {@code VkShaderModuleCreateInfo.pCode}. */
    public static ByteBuffer load(String name) {
        SlangPass pass = SlangPass.load(name).orElseThrow(() ->
            new IllegalStateException("missing AOT Slang pass '" + name + "' (compileSlang did not produce it)"));
        byte[] bytes = pass.spirv();
        ByteBuffer direct = BufferUtils.createByteBuffer(bytes.length);
        direct.put(bytes);
        direct.flip();
        return direct;
    }

    /** True when the pass is present in this build. */
    public static boolean available(String name) {
        return SlangPassLibrary.available(name);
    }
}
