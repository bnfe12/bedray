package io.github.andrexarlay.vulkancompat.rt.slang;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreNative;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * A Slang entry pass registered with the native Bedray core (ABI v2): one compute pipeline, with a separate
 * descriptor set for each recorded dispatch, recorded into a command buffer the host supplies.
 *
 * <p>Resources are addressed by the Slang parameter name from the generated manifest, not by a hard-coded binding
 * number, so a shader edit that moves a binding does not silently bind the wrong image.
 *
 * <p>The core inserts no barriers and never submits: the caller keeps the same synchronization it had with the
 * legacy pipeline (see {@code VkBarriers} in the RT frame).
 */
public final class SlangProgram implements AutoCloseable {
    /**
     * One descriptor write.
     *
     * @param name           Slang parameter name from the manifest
     * @param descriptorType {@code VkDescriptorType}
     * @param imageLayout    {@code VkImageLayout} for image descriptors, 0 for buffers
     * @param handle         image view or buffer handle
     * @param sampler        sampler handle for {@code COMBINED_IMAGE_SAMPLER}, 0 otherwise
     * @param offset         buffer offset, 0 for images
     * @param range          buffer range, 0 for images
     */
    public record Resource(String name, int descriptorType, int imageLayout, long handle, long sampler, long offset,
                           long range) {
        public static Resource sampled(String name, long imageView, long sampler) {
            return new Resource(name, BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                BedrayCoreLayouts.VK_IMAGE_LAYOUT_GENERAL, imageView, sampler, 0L, 0L);
        }

        public static Resource storageImage(String name, long imageView) {
            return new Resource(name, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE,
                BedrayCoreLayouts.VK_IMAGE_LAYOUT_GENERAL, imageView, 0L, 0L, 0L);
        }

        public static Resource storageBuffer(String name, long buffer, long offset, long range) {
            return new Resource(name, BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER, 0, buffer, 0L, offset, range);
        }

        public static Resource uniformBuffer(String name, long buffer, long offset, long range) {
            return new Resource(name, BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER, 0, buffer, 0L, offset, range);
        }

        public static Resource accelerationStructure(String name, long tlas) {
            return new Resource(name, BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR, 0, tlas, 0L, 0L, 0L);
        }
    }

    private final BedrayCoreNative library;
    private final long core;
    private final long handle;
    private final SlangPassManifest manifest;
    private boolean closed;

    private SlangProgram(BedrayCoreNative library, long core, long handle, SlangPassManifest manifest) {
        this.library = library;
        this.core = core;
        this.handle = handle;
        this.manifest = manifest;
    }

    /**
     * Registers {@code pass} with the core. The core creates the shader module, descriptor set layout, pipeline
     * layout and compute pipeline; nothing is submitted here.
     */
    public static SlangProgram register(BedrayCoreNative library, long core, SlangPass pass) {
        SlangPassManifest manifest = pass.manifest();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment spirv = pass.toNative(arena);
            MemorySegment bindings = MemorySegment.NULL;
            if (!manifest.bindings().isEmpty()) {
                bindings = arena.allocate(BedrayCoreLayouts.descriptorBindingSize() * manifest.bindings().size());
                for (int i = 0; i < manifest.bindings().size(); i++) {
                    SlangPassManifest.Binding binding = manifest.bindings().get(i);
                    BedrayCoreNative.writeDescriptorBinding(bindings, i, binding.binding(), binding.descriptorType(),
                        binding.count(), BedrayCoreLayouts.VK_SHADER_STAGE_COMPUTE_BIT);
                }
            }
            long handle = library.registerProgram(core, manifest.name(), manifest.entryPoint(), spirv, pass.wordCount(),
                bindings, manifest.bindings().size(), manifest.pushConstantBytes());
            return new SlangProgram(library, core, handle, manifest);
        }
    }

    public SlangPassManifest manifest() {
        return manifest;
    }

    public String name() {
        return manifest.name();
    }

    /** Dispatch group count covering {@code pixels} along {@code axis} (0 = x, 1 = y, 2 = z). */
    public int groups(int pixels, int axis) {
        return manifest.groups(pixels, axis);
    }

    /**
     * Records one dispatch into {@code commandBuffer}. {@code resources} must contain exactly one entry per manifest
     * binding, in any order; {@code pushConstants} must hold at least {@link SlangPassManifest#pushConstantBytes()}.
     */
    public void dispatch(long commandBuffer, int groupCountX, int groupCountY, int groupCountZ,
                         List<Resource> resources, ByteBuffer pushConstants) {
        if (closed) {
            throw new IllegalStateException("SlangProgram " + name() + " is closed");
        }
        List<SlangPassManifest.Binding> bindings = manifest.bindings();
        if (resources.size() != bindings.size()) {
            throw new IllegalArgumentException(name() + " expects " + bindings.size() + " resource(s), got " + resources.size());
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment array = MemorySegment.NULL;
            if (!bindings.isEmpty()) {
                array = arena.allocate(BedrayCoreLayouts.resourceSize() * bindings.size());
                for (int i = 0; i < bindings.size(); i++) {
                    SlangPassManifest.Binding binding = bindings.get(i);
                    Resource resource = find(resources, binding.name());
                    if (resource == null) {
                        throw new IllegalArgumentException(name() + " has no resource for binding '" + binding.name() + "'");
                    }
                    if (resource.descriptorType() != binding.descriptorType()) {
                        throw new IllegalArgumentException(name() + ": binding '" + binding.name() + "' is "
                            + BedrayCoreLayouts.descriptorTypeName(binding.descriptorType()) + ", got "
                            + BedrayCoreLayouts.descriptorTypeName(resource.descriptorType()));
                    }
                    BedrayCoreNative.writeResource(array, i, binding.binding(), binding.descriptorType(),
                        resource.imageLayout(), resource.handle(), resource.sampler(), resource.offset(), resource.range());
                }
            }
            int pushBytes = manifest.pushConstantBytes();
            MemorySegment push = MemorySegment.NULL;
            if (pushBytes > 0) {
                if (pushConstants == null || pushConstants.remaining() < pushBytes) {
                    throw new IllegalArgumentException(name() + " needs " + pushBytes + " push constant byte(s), got "
                        + (pushConstants == null ? 0 : pushConstants.remaining()));
                }
                push = MemorySegment.ofBuffer(pushConstants);
                if (push.byteSize() > pushBytes) {
                    push = push.asSlice(0, pushBytes);
                }
            }
            library.renderFrame(core, commandBuffer, handle, groupCountX, groupCountY, groupCountZ,
                array, bindings.size(), push, pushBytes);
        }
    }

    private static Resource find(List<Resource> resources, String name) {
        for (Resource resource : resources) {
            if (resource.name().equals(name)) {
                return resource;
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        library.destroyProgram(core, handle);
    }

    @Override
    public String toString() {
        return "SlangProgram[" + name() + ", " + manifest.bindings().size() + " binding(s), "
            + manifest.pushConstantBytes() + " B push]";
    }
}
