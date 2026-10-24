package io.github.andrexarlay.bedray.compute;

import io.github.andrexarlay.bedray.api.v1.ComputeFrameContext;
import io.github.andrexarlay.bedray.api.v1.VoxelVolume;
import io.github.andrexarlay.bedray.compute.voxel.VoxelEmitters;
import io.github.andrexarlay.bedray.compute.voxel.VoxelWorldUpload;
import io.github.andrexarlay.bedray.api.v1.CameraSnapshot;
import io.github.andrexarlay.bedray.api.v1.AtlasFrame;
import io.github.andrexarlay.bedray.api.v1.CubeSectionMaterials;
import io.github.andrexarlay.bedray.api.v1.SectionModelGeometry;
import io.github.andrexarlay.bedray.api.v1.LightingFrame;
import io.github.andrexarlay.bedray.api.v1.RasterFrame;
import io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry;
import java.nio.ByteOrder;
import java.util.List;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/** Owns only compute resources on the host's device. All commands use the host graphics queue. */
public final class VoxelComputePipeline implements AutoCloseable {
    private final VkDevice device;
    private final int width, height, targetFormat;
    private final boolean liveCapacity;
    private final AtlasFrame atlas;
    private final RasterFrame raster;
    private long gridBuffer, gridMemory, image, imageMemory, view;
    private long paletteBuffer, paletteMemory, sampler;
    private long modelBuffer, modelMemory, lightingBuffer, lightingMemory;
    private long shader, setLayout, pipelineLayout, pipeline, pool, descriptorSet;
    private final long[] historyImage = new long[2], historyMemory = new long[2], historyView = new long[2];
    private boolean initialized;
    private ByteBuffer tileScratch;
    private long gridBytes;
    private final TemporalHistory history = new TemporalHistory();

    /** Frame SSBO (binding 5): LightingFrame, previous view-projection, previous camera and eye,
     * frame info (emitter count, history valid, history cap, motion), then the bounded emitter list. */
    public static final int FRAME_HEADER_BYTES = LightingFrame.BYTES + 64 + 16 + 16 + 16;
    public static final int FRAME_BYTES = FRAME_HEADER_BYTES + VoxelEmitters.BYTES;
    /** History texel: half rgb radiance + sample count, hit distance, octahedral normal. */
    static final int HISTORY_FORMAT = VK_FORMAT_R32G32B32A32_UINT;

    public VoxelComputePipeline(VkDevice device, int width, int height, int targetFormat, VoxelVolume grid) {
        this(device, width, height, targetFormat, grid, null);
    }

    public VoxelComputePipeline(VkDevice device, int width, int height, int targetFormat, VoxelVolume grid,
                                AtlasFrame atlas) {
        this(device,width,height,targetFormat,grid,atlas,null);
    }

    public VoxelComputePipeline(VkDevice device, int width, int height, int targetFormat, VoxelVolume grid,
                                AtlasFrame atlas, RasterFrame raster) {
        this.device = device;
        this.width = width;
        this.height = height;
        this.targetFormat = targetFormat;
        this.atlas = atlas;
        this.raster = raster;
        this.liveCapacity = grid.sizeX() == 48 && grid.sizeY() == 48 && grid.sizeZ() == 48
                && grid.cellCount() == 48*48*48 && grid.byteSize() == 16L+48L*48*48*4;
        try {
            if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid output dimensions");
            if (atlas != null && (!liveCapacity || atlas.deviceAddress() != device.address()))
                throw new IllegalArgumentException("Textured cubes require a live volume and same-device atlas");
            if (raster != null && (atlas == null || raster.deviceAddress() != device.address()
                    || raster.width() != width || raster.height() != height || raster.depthFormat() != VK_FORMAT_D32_SFLOAT
                    || !colorFormat(raster.colorFormat())))
                throw new IllegalArgumentException("Raster preservation requires matching sampled color and reverse-Z D32 depth");
            create(grid);
        } catch (RuntimeException | Error failure) {
            destroy();
            throw failure;
        }
    }

    public boolean matches(ComputeFrameContext frame) {
        AtlasFrame next = frame.sceneMode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE ? frame.atlas() : null;
        RasterFrame nextRaster = frame.sceneMode() == GraphicsBackendRegistry.SceneMode.LIVE_OPAQUE ? frame.raster() : null;
        return device.address() == frame.device().address() && width == frame.width()
                && height == frame.height() && targetFormat == frame.targetFormat()
                && java.util.Objects.equals(atlas, next) && java.util.Objects.equals(raster,nextRaster);
    }

    private static boolean colorFormat(int format) {
        return format == VK_FORMAT_R8G8B8A8_UNORM || format == VK_FORMAT_B8G8R8A8_UNORM
            || format == VK_FORMAT_R8G8B8A8_SRGB || format == VK_FORMAT_B8G8R8A8_SRGB;
    }

    public static String shaderSource() {
        try (var stream = VoxelComputePipeline.class.getResourceAsStream("/assets/bedray_compute/shaders/voxel.comp")) {
            if (stream == null) throw new IllegalStateException("Missing voxel.comp");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read voxel.comp", failure);
        }
    }

    private void create(VoxelVolume grid) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (!colorFormat(targetFormat)) {
                throw new IllegalArgumentException("Compute diagnostic requires an 8-bit UNORM/sRGB target; HDR integration is pending");
            }
            VkPhysicalDevice physical = device.getPhysicalDevice();
            VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
            vkGetPhysicalDeviceProperties(physical, properties);
            // Live textured grids carry the per-cell tint after the occupancy tiles.
            gridBytes = grid.byteSize() + (atlas != null ? VoxelWorldUpload.TINT_BYTES : 0);
            if (width > properties.limits().maxImageDimension2D() || height > properties.limits().maxImageDimension2D()
                    || gridBytes > Integer.toUnsignedLong(properties.limits().maxStorageBufferRange())
                    || atlas != null && VoxelWorldUpload.MODEL_BUFFER_BYTES > Integer.toUnsignedLong(properties.limits().maxStorageBufferRange())) {
                throw new IllegalArgumentException("Compute scene exceeds device limits");
            }
            if (atlas != null && (properties.limits().maxPerStageDescriptorStorageBuffers() < 4
                    || properties.limits().maxDescriptorSetStorageBuffers() < 4
                    || properties.limits().maxPerStageDescriptorSamplers() < (raster == null ? 1 : 3)
                    || properties.limits().maxPerStageDescriptorSampledImages() < (raster == null ? 1 : 3)
                    || properties.limits().maxPerStageDescriptorStorageImages() < (raster == null ? 1 : 3)))
                throw new IllegalArgumentException("Compute materials exceed device descriptor limits");
            VkFormatProperties format = VkFormatProperties.calloc(stack);
            vkGetPhysicalDeviceFormatProperties(physical, VK_FORMAT_R8G8B8A8_UNORM, format);
            int required = VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT | VK_FORMAT_FEATURE_BLIT_SRC_BIT;
            if ((format.optimalTilingFeatures() & required) != required) throw new IllegalStateException("RGBA8 storage/blit unsupported");
            vkGetPhysicalDeviceFormatProperties(physical, targetFormat, format);
            if ((format.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) throw new IllegalStateException("Target blit unsupported");
            if (atlas != null) requireSampledFormat(physical,atlas.vkFormat(),format);
            if (raster != null) {
                requireSampledFormat(physical,raster.colorFormat(),format);
                requireSampledFormat(physical,raster.depthFormat(),format);
                vkGetPhysicalDeviceFormatProperties(physical, HISTORY_FORMAT, format);
                if ((format.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0)
                    throw new IllegalStateException("RGBA32UI storage images unsupported for temporal history");
            }

            LongBuffer handle = stack.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(gridBytes)
                    .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "create grid buffer");
            gridBuffer = handle.get(0);
            VkMemoryRequirements requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, gridBuffer, requirements);
            gridMemory = allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
            check(vkBindBufferMemory(device, gridBuffer, gridMemory, 0), "bind grid memory");
            var pointer = stack.mallocPointer(1);
            check(vkMapMemory(device, gridMemory, 0, gridBytes, 0, pointer), "map grid memory");
            try {
                grid.writeTo(MemoryUtil.memByteBuffer(pointer.get(0), Math.toIntExact(grid.byteSize())));
                if (gridBytes > grid.byteSize())
                    MemoryUtil.memSet(pointer.get(0) + grid.byteSize(), 0, gridBytes - grid.byteSize());
            } finally { vkUnmapMemory(device, gridMemory); }

            if (atlas != null) {
                check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default().size(VoxelWorldUpload.PALETTE_BYTES)
                        .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, handle), "create cube palette buffer");
                paletteBuffer = handle.get(0);
                vkGetBufferMemoryRequirements(device, paletteBuffer, requirements);
                paletteMemory = allocate(requirements, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, stack);
                check(vkBindBufferMemory(device, paletteBuffer, paletteMemory, 0), "bind cube palette memory");
                check(vkMapMemory(device, paletteMemory, 0, VoxelWorldUpload.PALETTE_BYTES, 0, pointer), "map cube palette memory");
                try { MemoryUtil.memSet(pointer.get(0), 0, VoxelWorldUpload.PALETTE_BYTES); }
                finally { vkUnmapMemory(device, paletteMemory); }
                long[] model = createStorage(VoxelWorldUpload.MODEL_BUFFER_BYTES,stack);
                modelBuffer = model[0]; modelMemory = model[1];
                long[] light = createStorage(FRAME_BYTES,stack);
                lightingBuffer = light[0]; lightingMemory = light[1];
                check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK_FILTER_NEAREST).minFilter(VK_FILTER_NEAREST)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0).maxLod(0).anisotropyEnable(false).compareEnable(false), null, handle), "create cube atlas sampler");
                sampler = handle.get(0);
            }

            VkImageCreateInfo info = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D)
                    .format(VK_FORMAT_R8G8B8A8_UNORM).mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL).usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            info.extent().set(width, height, 1);
            check(vkCreateImage(device, info, null, handle), "create output image");
            image = handle.get(0);
            vkGetImageMemoryRequirements(device, image, requirements);
            imageMemory = allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
            check(vkBindImageMemory(device, image, imageMemory, 0), "bind output memory");
            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM);
            viewInfo.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            check(vkCreateImageView(device, viewInfo, null, handle), "create output view");
            view = handle.get(0);
            if (raster != null) for (int i = 0; i < 2; i++) {
                info.format(HISTORY_FORMAT).usage(VK_IMAGE_USAGE_STORAGE_BIT);
                check(vkCreateImage(device, info, null, handle), "create history image");
                historyImage[i] = handle.get(0);
                vkGetImageMemoryRequirements(device, historyImage[i], requirements);
                historyMemory[i] = allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, stack);
                check(vkBindImageMemory(device, historyImage[i], historyMemory[i], 0), "bind history memory");
                viewInfo.image(historyImage[i]).format(HISTORY_FORMAT);
                check(vkCreateImageView(device, viewInfo, null, handle), "create history view");
                historyView[i] = handle.get(0);
            }

            String source = shaderSource();
            if (atlas != null) source = source.replace("#version 450", "#version 450\n#define TEXTURED_CUBES 1");
            if (raster != null) source = source.replace("#version 450", "#version 450\n#define RASTER_INPUTS 1");
            ByteBuffer spirv = ComputeShaderCompiler.compile(source, "bedray_compute/voxel.comp");
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv), null, handle), "create shader");
            shader = handle.get(0);
            int bindingCount = atlas == null ? 2 : raster == null ? 6 : 10;
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(bindingCount, stack);
            bindings.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            if (atlas != null) {
                bindings.get(2).binding(2).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                bindings.get(3).binding(3).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                bindings.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                bindings.get(5).binding(5).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                if (raster != null) for (int binding = 6; binding < 8; binding++)
                    bindings.get(binding).binding(binding).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
                if (raster != null) for (int binding = 8; binding < 10; binding++)
                    bindings.get(binding).binding(binding).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings), null, handle), "create descriptor layout");
            setLayout = handle.get(0);
            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(112);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, handle), "create pipeline layout");
            pipelineLayout = handle.get(0);
            VkComputePipelineCreateInfo.Buffer pipelines = VkComputePipelineCreateInfo.calloc(1, stack);
            pipelines.get(0).sType$Default().layout(pipelineLayout);
            pipelines.get(0).stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shader).pName(stack.UTF8("main"));
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, pipelines, null, handle), "create compute pipeline");
            pipeline = handle.get(0);
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(atlas == null ? 2 : 3, stack);
            sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(raster == null ? 1 : 3);
            sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(atlas == null ? 1 : 4);
            if (atlas != null) sizes.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(raster == null ? 1 : 3);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(sizes), null, handle), "create descriptor pool");
            pool = handle.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), handle), "allocate descriptors");
            descriptorSet = handle.get(0);
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.get(0).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.get(0).buffer(gridBuffer).offset(0).range(gridBytes);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(bindingCount, stack);
            writes.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(imageInfo);
            writes.get(1).sType$Default().dstSet(descriptorSet).dstBinding(1).descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(bufferInfo);
            if (atlas != null) {
                VkDescriptorBufferInfo.Buffer paletteInfo = VkDescriptorBufferInfo.calloc(1, stack);
                paletteInfo.get(0).buffer(paletteBuffer).offset(0).range(VoxelWorldUpload.PALETTE_BYTES);
                writes.get(2).sType$Default().dstSet(descriptorSet).dstBinding(2).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(paletteInfo);
                VkDescriptorImageInfo.Buffer atlasInfo = VkDescriptorImageInfo.calloc(1, stack);
                atlasInfo.get(0).sampler(sampler).imageView(atlas.imageView()).imageLayout(atlas.imageLayout());
                writes.get(3).sType$Default().dstSet(descriptorSet).dstBinding(3).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(atlasInfo);
                VkDescriptorBufferInfo.Buffer modelsInfo = VkDescriptorBufferInfo.calloc(1,stack);
                modelsInfo.get(0).buffer(modelBuffer).offset(0).range(VoxelWorldUpload.MODEL_BUFFER_BYTES);
                writes.get(4).sType$Default().dstSet(descriptorSet).dstBinding(4).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(modelsInfo);
                VkDescriptorBufferInfo.Buffer lightInfo = VkDescriptorBufferInfo.calloc(1,stack);
                lightInfo.get(0).buffer(lightingBuffer).offset(0).range(FRAME_BYTES);
                writes.get(5).sType$Default().dstSet(descriptorSet).dstBinding(5).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).pBufferInfo(lightInfo);
                if (raster != null) {
                    VkDescriptorImageInfo.Buffer colorInfo = VkDescriptorImageInfo.calloc(1,stack);
                    colorInfo.get(0).sampler(sampler).imageView(raster.colorView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(6).sType$Default().dstSet(descriptorSet).dstBinding(6).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(colorInfo);
                    VkDescriptorImageInfo.Buffer depthInfo = VkDescriptorImageInfo.calloc(1,stack);
                    depthInfo.get(0).sampler(sampler).imageView(raster.depthView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                    writes.get(7).sType$Default().dstSet(descriptorSet).dstBinding(7).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(depthInfo);
                    for (int i = 0; i < 2; i++) {
                        VkDescriptorImageInfo.Buffer historyInfo = VkDescriptorImageInfo.calloc(1,stack);
                        historyInfo.get(0).imageView(historyView[i]).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
                        writes.get(8+i).sType$Default().dstSet(descriptorSet).dstBinding(8+i).descriptorCount(1)
                            .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(historyInfo);
                    }
                }
            }
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private static void requireSampledFormat(VkPhysicalDevice physical, int format, VkFormatProperties properties) {
        vkGetPhysicalDeviceFormatProperties(physical,format,properties);
        if ((properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) == 0)
            throw new IllegalStateException("Borrowed format cannot be sampled: " + format);
    }

    private long[] createStorage(int bytes, MemoryStack stack) {
        LongBuffer handle = stack.mallocLong(1);
        check(vkCreateBuffer(device,VkBufferCreateInfo.calloc(stack).sType$Default().size(bytes)
            .usage(VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE),null,handle),"create material storage");
        long buffer = handle.get(0), memory = 0;
        try {
            var requirements = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device,buffer,requirements);
            memory = allocate(requirements,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,stack);
            check(vkBindBufferMemory(device,buffer,memory,0),"bind material storage");
            // Device-local geometry avoids repeated model reads across a host-memory bus.
            // The first record clears headers; dense referenced quads and lighting are uploaded
            // before any shader reads. Unused quad tails remain unreachable through zero headers.
            return new long[]{buffer,memory};
        } catch (RuntimeException | Error error) {
            vkDestroyBuffer(device,buffer,null);
            if (memory != 0) vkFreeMemory(device,memory,null);
            throw error;
        }
    }

    private long allocate(VkMemoryRequirements requirements, int flags, MemoryStack stack) {
        VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(device.getPhysicalDevice(), memory);
        for (int i = 0; i < memory.memoryTypeCount(); i++) {
            if ((requirements.memoryTypeBits() & (1 << i)) != 0 && (memory.memoryTypes(i).propertyFlags() & flags) == flags) {
                LongBuffer handle = stack.mallocLong(1);
                check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(requirements.size()).memoryTypeIndex(i), null, handle), "allocate memory");
                return handle.get(0);
            }
        }
        throw new IllegalStateException("No compatible compute memory type for flags " + flags);
    }

    /** Target must be a single-sample color image, TRANSFER_DST usage, in GENERAL on this queue. */
    public void record(VkCommandBuffer command, long target) {
        record(command, target, false);
    }

    void record(VkCommandBuffer command, long target, boolean hitIds) {
        record(command, target, hitIds, null, null, List.of(),null);
    }

    public void recordWorld(VkCommandBuffer command, long target, CameraSnapshot camera,
                            VoxelWorldUpload origin, List<VoxelWorldUpload.Tile> updates) {
        java.util.Objects.requireNonNull(camera);
        java.util.Objects.requireNonNull(origin);
        record(command,target,false,camera,origin,updates,null);
    }

    public void recordWorld(VkCommandBuffer command, long target, CameraSnapshot camera,
                            VoxelWorldUpload origin, List<VoxelWorldUpload.Tile> updates, LightingFrame lighting) {
        java.util.Objects.requireNonNull(camera);
        java.util.Objects.requireNonNull(origin);
        record(command,target,false,camera,origin,updates,lighting);
    }

    void recordWorld(VkCommandBuffer command, long target, boolean hitIds, CameraSnapshot camera,
                     VoxelWorldUpload origin, List<VoxelWorldUpload.Tile> updates) {
        java.util.Objects.requireNonNull(camera);
        java.util.Objects.requireNonNull(origin);
        record(command,target,hitIds,camera,origin,updates,null);
    }

    private void record(VkCommandBuffer command, long target, boolean hitIds, CameraSnapshot camera,
                        VoxelWorldUpload origin, List<VoxelWorldUpload.Tile> updates, LightingFrame lighting) {
        if (camera != null && !liveCapacity) throw new IllegalArgumentException("Live traversal requires a 48 cubed tile buffer");
        if (atlas != null) {
            for (var tile : updates) {
                if (tile.section() != null && tile.section().materialGeneration() != 0
                        && tile.section().materialGeneration() != atlas.generation())
                    throw new IllegalArgumentException("Materials do not match the borrowed atlas generation");
            }
        }
        // Validate camera data before recording any resource updates.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (camera != null) camera.writePushConstants(stack.calloc(112),origin.originX(),origin.originY(),origin.originZ());
        }
        // Reprojection inputs are computed before recording; a singular previous matrix only disables history.
        if (raster != null) history.prepare(camera, origin, !updates.isEmpty(),
            GraphicsBackendRegistry.debugView() == GraphicsBackendRegistry.DebugView.SHADED);
        if (!updates.isEmpty() || atlas != null) {
            // vkCmdUpdateBuffer copies bytes into the command buffer immediately. No persistent mapped
            // staging range is overwritten while an earlier frame might still be reading it.
            if (tileScratch == null) tileScratch = MemoryUtil.memAlloc(atlas == null
                ? VoxelWorldUpload.TILE_BYTES : SectionModelGeometry.SECTION_QUAD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer upload = tileScratch;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferMemoryBarrier.Buffer barrier = VkBufferMemoryBarrier.calloc(atlas == null ? 1 : 4,stack);
                barrier.get(0).sType$Default().buffer(gridBuffer).offset(0).size(VK_WHOLE_SIZE)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                if (atlas != null) barrier.get(1).sType$Default().buffer(paletteBuffer).offset(0).size(VK_WHOLE_SIZE)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                if (atlas != null) {
                    barrier.get(2).sType$Default().buffer(modelBuffer).offset(0).size(VK_WHOLE_SIZE)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    barrier.get(3).sType$Default().buffer(lightingBuffer).offset(0).size(VK_WHOLE_SIZE)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .srcAccessMask(VK_ACCESS_SHADER_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                }
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,null,barrier,null);
                if (atlas != null && !initialized) {
                    vkCmdFillBuffer(command,modelBuffer,0,VoxelWorldUpload.MODEL_BUFFER_HEADER_BYTES,0);
                    VkMemoryBarrier.Buffer clearBarrier = VkMemoryBarrier.calloc(1,stack);
                    clearBarrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                    vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                        0,clearBarrier,null,null);
                }
                for (var tile : updates) {
                    upload.clear(); tile.writeTo(upload); upload.flip();
                    vkCmdUpdateBuffer(command,gridBuffer,tile.offset(),upload);
                    if (atlas != null) {
                        upload.clear(); tile.writeMaterialsTo(upload); upload.flip();
                        vkCmdUpdateBuffer(command,paletteBuffer,tile.materialOffset(),upload);
                        upload.clear(); tile.writeTintsTo(upload); upload.flip();
                        vkCmdUpdateBuffer(command,gridBuffer,tile.tintOffset(),upload);
                        upload.clear(); tile.writeModelHeadersTo(upload); upload.flip();
                        vkCmdUpdateBuffer(command,modelBuffer,tile.modelHeaderOffset(),upload);
                        upload.clear(); tile.writeModelQuadsTo(upload); upload.flip();
                        long offset = tile.modelQuadOffset();
                        while (upload.hasRemaining()) {
                            int count = Math.min(65536,upload.remaining());
                            ByteBuffer span = upload.slice().limit(count);
                            vkCmdUpdateBuffer(command,modelBuffer,offset,span);
                            upload.position(upload.position()+count);
                            offset += count;
                        }
                    }
                }
                if (atlas != null) {
                    upload.clear();
                    for (int component = 0; component < LightingFrame.FLOAT_COUNT; component++)
                        upload.putFloat(lighting == null ? 0 : lighting.component(component));
                    VoxelEmitters emitters = origin == null ? null : origin.emitters();
                    history.writeFrame(upload, emitters == null ? 0 : emitters.count());
                    upload.flip();
                    vkCmdUpdateBuffer(command,lightingBuffer,0,upload);
                    boolean emittersChanged = origin != null && origin.consumeEmittersChanged();
                    if (emitters != null && (emittersChanged || !initialized)) {
                        upload.clear(); emitters.writeTo(upload); upload.flip();
                        vkCmdUpdateBuffer(command,lightingBuffer,FRAME_HEADER_BYTES,upload);
                    }
                }
                for (int i = 0; i < barrier.capacity(); i++)
                    barrier.get(i).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,null,barrier,null);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (raster != null) {
                // Inputs stay GENERAL. Host color/depth writes must finish before sampled reads;
                // the later target barrier also orders these reads before the color copy-back.
                VkMemoryBarrier.Buffer inputBarrier = VkMemoryBarrier.calloc(1,stack);
                inputBarrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(command,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0,inputBarrier,null,null);
            }
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            var b = barrier.get(0).sType$Default().image(image).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).oldLayout(initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(initialized ? VK_ACCESS_TRANSFER_READ_BIT : 0)
                    .dstAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            b.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, initialized ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, barrier);
            if (raster != null) {
                // History written by the previous dispatch is read (and the other image written) by this one.
                VkImageMemoryBarrier.Buffer historyBarrier = VkImageMemoryBarrier.calloc(2, stack);
                for (int i = 0; i < 2; i++) {
                    var h = historyBarrier.get(i).sType$Default().image(historyImage[i])
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .oldLayout(initialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED)
                        .newLayout(VK_IMAGE_LAYOUT_GENERAL)
                        .srcAccessMask(initialized ? VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT : 0)
                        .dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    h.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                }
                vkCmdPipelineBarrier(command, initialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, null, null, historyBarrier);
            }
            vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            ByteBuffer push = stack.calloc(112).order(ByteOrder.LITTLE_ENDIAN);
            if (camera != null) {
                camera.writePushConstants(push,origin.originX(),origin.originY(),origin.originZ());
            }
            boolean srgbTarget = targetFormat == VK_FORMAT_R8G8B8A8_SRGB || targetFormat == VK_FORMAT_B8G8R8A8_SRGB;
            push.putInt(80,(hitIds ? 1 : 0) | (camera != null ? 2 : 0) | (srgbTarget ? 4 : 0)
                | (lighting != null && atlas != null ? 8 : 0)
                | (raster != null && (raster.colorFormat() == VK_FORMAT_R8G8B8A8_SRGB
                    || raster.colorFormat() == VK_FORMAT_B8G8R8A8_SRGB) ? 16 : 0)
                | (raster != null && history.parity() ? 32 : 0));
            push.putInt(84,io.github.andrexarlay.bedray.api.v1.GraphicsBackendRegistry.debugView().ordinal());
            push.putInt(88,(int) history.frameCounter());
            vkCmdPushConstants(command, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            vkCmdDispatch(command, (width + 7) / 8, (height + 7) / 8, 1);
            b.oldLayout(VK_IMAGE_LAYOUT_GENERAL).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, barrier);
            VkImageMemoryBarrier.Buffer targetBarrier = VkImageMemoryBarrier.calloc(1, stack);
            var t = targetBarrier.get(0).sType$Default().image(target).oldLayout(VK_IMAGE_LAYOUT_GENERAL).newLayout(VK_IMAGE_LAYOUT_GENERAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT | VK_ACCESS_MEMORY_READ_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            t.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, targetBarrier);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).srcOffsets(1).set(width, height, 1);
            blit.get(0).dstOffsets(1).set(width, height, 1);
            vkCmdBlitImage(command, image, VK_IMAGE_LAYOUT_GENERAL, target, VK_IMAGE_LAYOUT_GENERAL, blit, VK_FILTER_NEAREST);
            t.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, targetBarrier);
            initialized = true;
            if (raster != null) history.recorded();
        }
    }

    /** Drops accumulated history, e.g. on a new world session; the next frame starts from one sample. */
    public void invalidateHistory() { history.invalidate(); }

    /** CPU view of temporal accumulation for diagnostics; per-pixel validation happens on the GPU. */
    public String historyStatus() { return raster == null ? "off (no raster inputs)" : history.status(); }

    @Override public void close() {
        // Only resize, mode changes and teardown wait; steady-state frames never wait for the CPU.
        check(vkDeviceWaitIdle(device), "wait before compute resource retirement");
        destroy();
    }

    private void destroy() {
        if (pool != 0) vkDestroyDescriptorPool(device, pool, null);
        if (sampler != 0) vkDestroySampler(device, sampler, null);
        if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
        if (pipelineLayout != 0) vkDestroyPipelineLayout(device, pipelineLayout, null);
        if (setLayout != 0) vkDestroyDescriptorSetLayout(device, setLayout, null);
        if (shader != 0) vkDestroyShaderModule(device, shader, null);
        if (view != 0) vkDestroyImageView(device, view, null);
        if (image != 0) vkDestroyImage(device, image, null);
        if (imageMemory != 0) vkFreeMemory(device, imageMemory, null);
        if (gridBuffer != 0) vkDestroyBuffer(device, gridBuffer, null);
        if (gridMemory != 0) vkFreeMemory(device, gridMemory, null);
        if (paletteBuffer != 0) vkDestroyBuffer(device, paletteBuffer, null);
        if (paletteMemory != 0) vkFreeMemory(device, paletteMemory, null);
        if (modelBuffer != 0) vkDestroyBuffer(device,modelBuffer,null);
        if (modelMemory != 0) vkFreeMemory(device,modelMemory,null);
        if (lightingBuffer != 0) vkDestroyBuffer(device,lightingBuffer,null);
        if (lightingMemory != 0) vkFreeMemory(device,lightingMemory,null);
        for (int i = 0; i < 2; i++) {
            if (historyView[i] != 0) vkDestroyImageView(device, historyView[i], null);
            if (historyImage[i] != 0) vkDestroyImage(device, historyImage[i], null);
            if (historyMemory[i] != 0) vkFreeMemory(device, historyMemory[i], null);
            historyView[i] = historyImage[i] = historyMemory[i] = 0;
        }
        if (tileScratch != null) { MemoryUtil.memFree(tileScratch); tileScratch = null; }
        pool = pipeline = pipelineLayout = setLayout = shader = view = image = imageMemory = gridBuffer = gridMemory = 0;
        sampler = paletteBuffer = paletteMemory = 0;
        modelBuffer = modelMemory = lightingBuffer = lightingMemory = 0;
    }

    static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed: VkResult=" + result);
    }
}
