package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bloom and sun glare on the final image ({@code rt_bloom.comp}, see its header): prefilter + 13 tap downsample chain of
 * {@link #LEVELS} RGBA16F mips, in-place scatter-weighted upsample, then one apply dispatch that adds the glow (and the optional sun
 * halo) before the tone map shoulder. Like {@link RtVolumetricPass} it only reads and writes the final image after the denoiser, so
 * no guide buffer of NRD / DLSS Ray Reconstruction is touched. All sizes derive from the image size, so the glow radius scales with
 * the render resolution and works the same with an upscaler (applied at render resolution, before the upscale).
 *
 * <p>Two implementations of the same behaviour share this class: the AOT Slang programs ({@code passes/bloom_*.slang}) when the
 * native core and all four manifests are present, and the legacy GLSL {@code rt_bloom.comp} otherwise. The mips, samplers and push
 * constant block are host-owned in both, so the frame code and the memory budget do not depend on which one runs.
 */
final class RtBloomPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    static final int LEVELS = 6;
    static final int PUSH_BYTES = 128;
    private static final int STAGES = 4;
    private static final String[] STAGE_NAMES = {"prefilter_down", "down", "up", "apply"};
    private static final String[] SLANG_NAMES = {"bloom_prefilter_down", "bloom_down", "bloom_up", "bloom_apply"};

    /** One binding the host promises to supply, checked against the generated manifest before the first dispatch. */
    private record Expected(String name, int descriptorType) {
    }

    private static final Expected SRC = new Expected("srcTex", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
    private static final Expected DST = new Expected("dstImg", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
    private static final Expected FINAL = new Expected("finalImg", BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
    private static final Expected DEPTH = new Expected("depthTex", BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);

    /** Everything the apply stage needs per frame (all but {@code strength} are inputs of the sun glare). */
    record Params(Matrix4f invViewProj, float[] sunDir, float glareStrength, float camX, float camY, float camZ,
                  boolean flipY, boolean tonemapped, float threshold, float knee, float strength, float scatter, float sunNdcX,
                  float sunNdcY) {
    }

    private final VkDevice device;
    private final SlangRuntime slangRuntime;
    /** Four AOT programs in stage order, or {@code null} when this pass runs the legacy GLSL path. */
    private final SlangProgram[] slangPrograms;
    private final long[] shaderModules = new long[STAGES];
    private final long[] pipelines = new long[STAGES];
    private long setLayout;
    private long pipelineLayout;
    private final long linearSampler;
    private final long nearestSampler;
    private long pool;
    private final long[] sets = new long[2 * LEVELS];   // down0, down1..L-1, up L-2..0, apply
    /** Command buffers with live native recordings; released before the handle is reused or the mips are recreated. */
    private final Set<Long> recordings = new LinkedHashSet<>();
    private RtMemory memory;
    private final RtMemory.Img[] mips = new RtMemory.Img[LEVELS];
    private long boundOutput;
    private long boundDepth;
    private int w;
    private int h;

    RtBloomPass(VkDevice device, SlangRuntime slangRuntime) {
        this.device = device;
        this.slangRuntime = slangRuntime;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR).mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0f).maxLod(0f);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "create_bloom_sampler");
            linearSampler = p.get(0);
            si.magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "create_bloom_sampler_nearest");
            nearestSampler = p.get(0);

            this.slangPrograms = trySlang(slangRuntime);
            if (slangPrograms != null) {
                LOGGER.info("[VKB-ATMO] bloom on AOT Slang passes {} (legacy GLSL path not used)", String.join(", ", SLANG_NAMES));
                return;
            }

            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(4, stack);
            int[] types = {VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER};
            for (int i = 0; i < 4; i++) {
                b.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo li = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b);
            RtFailure.check(VK10.vkCreateDescriptorSetLayout(device, li, null, p), "create_bloom_set_layout");
            setLayout = p.get(0);

            VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
            push.get(0).stageFlags(VK10.VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(PUSH_BYTES);
            VkPipelineLayoutCreateInfo pl = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push);
            RtFailure.check(VK10.vkCreatePipelineLayout(device, pl, null, p), "create_bloom_pipeline_layout");
            pipelineLayout = p.get(0);

            for (int s = 0; s < STAGES; s++) {
                ByteBuffer spirv = RtShaderCompiler.compileResource("/shaders/glsl/rt_bloom.comp", "rt_bloom.comp+" + STAGE_NAMES[s],
                    "#define BLOOM_STAGE " + s + (char) 10);
                VkShaderModuleCreateInfo mi = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                RtFailure.check(VK10.vkCreateShaderModule(device, mi, null, p), "create_bloom_shader_module");
                shaderModules[s] = p.get(0);
                VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack);
                ci.get(0).sType$Default().layout(pipelineLayout);
                ci.get(0).stage().sType$Default().stage(VK10.VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModules[s]).pName(stack.UTF8("main"));
                RtFailure.check(RtVulkanPipelineCache.createCompute(device, ci, null, p), "create_bloom_pipeline");
                pipelines[s] = p.get(0);
            }
        }
    }

    /**
     * Registers the four AOT programs and checks their generated manifests against the resources this class can supply.
     * Returns {@code null} on any mismatch: the caller then keeps the legacy GLSL path instead of dispatching with a
     * wrong layout. Programs that were registered before the failure stay in the runtime cache and are simply unused.
     */
    private static SlangProgram[] trySlang(SlangRuntime runtime) {
        if (runtime == null) {
            return null;
        }
        try {
            SlangProgram[] programs = new SlangProgram[STAGES];
            for (int s = 0; s < STAGES; s++) {
                Optional<SlangProgram> pass = runtime.program(SLANG_NAMES[s]);
                if (pass.isEmpty()) {
                    throw new IllegalStateException("missing pass " + SLANG_NAMES[s]);
                }
                programs[s] = pass.get();
            }
            require(programs[0], SRC, DST, DEPTH);
            require(programs[1], SRC, DST);
            require(programs[2], SRC, DST);
            require(programs[3], SRC, FINAL, DEPTH);
            return programs;
        } catch (RuntimeException e) {
            LOGGER.warn("[VKB-ATMO] bloom Slang path unavailable, falling back to rt_bloom.comp: {}", e.getMessage());
            return null;
        }
    }

    /** The manifest is generated from the shader: the host must supply exactly these bindings and the 128 B block. */
    private static void require(SlangProgram program, Expected... expected) {
        SlangPassManifest manifest = program.manifest();
        if (manifest.pushConstantBytes() != PUSH_BYTES) {
            throw new IllegalStateException(program.name() + " pushes " + manifest.pushConstantBytes()
                + " B, host block is " + PUSH_BYTES + " B");
        }
        if (manifest.bindings().size() != expected.length) {
            throw new IllegalStateException(program.name() + " declares " + manifest.bindings().size()
                + " binding(s), host supplies " + expected.length);
        }
        for (Expected want : expected) {
            SlangPassManifest.Binding binding = manifest.binding(want.name());
            if (binding == null) {
                throw new IllegalStateException(program.name() + " has no '" + want.name() + "' binding");
            }
            if (binding.descriptorType() != want.descriptorType()) {
                throw new IllegalStateException(program.name() + ": '" + want.name() + "' is "
                    + BedrayCoreLayouts.descriptorTypeName(binding.descriptorType()) + ", host supplies "
                    + BedrayCoreLayouts.descriptorTypeName(want.descriptorType()));
            }
        }
    }

    private boolean slang() {
        return slangPrograms != null;
    }

    /** Size of mip {@code i} (0 = half resolution) of a {@code size}-wide image. */
    static int mipSize(int size, int i) {
        return Math.max(1, (size + (1 << (i + 1)) - 1) >> (i + 1));
    }

    /** (Re)creates the mips and the descriptor sets when the size or one of the views changed. */
    private void ensure(VkCommandBuffer cmd, RtMemory mem, int width, int height, long outputView, long depthView) {
        if (slang()) {
            // The native core allocates a descriptor set per recorded dispatch, so a changed output or depth view
            // needs no host-side rewrite: only the mips are shared state.
            ensureMips(cmd, mem, width, height);
            return;
        }
        boolean sizeChanged = mips[0] == null || w != width || h != height;
        if (!sizeChanged && boundOutput == outputView && boundDepth == depthView) {
            return;
        }
        VK10.vkDeviceWaitIdle(device); // descriptor sets and images may still be used by frames in flight
        memory = mem;
        if (sizeChanged) {
            for (int i = 0; i < LEVELS; i++) {
                if (mips[i] != null) {
                    mem.destroy(mips[i]);
                }
                mips[i] = mem.createImage("rt_bloom_mip" + i, mipSize(width, i), mipSize(height, i), VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
                VkBarriers.transition(cmd, mips[i].image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                    VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
            w = width;
            h = height;
            LOGGER.info("[VKB-ATMO] rt_bloom {}x{} mips={} smallest={}x{} RGBA16F", width, height, LEVELS,
                mips[LEVELS - 1].width, mips[LEVELS - 1].height);
        }
        if (pool != 0L) {
            VK10.vkDestroyDescriptorPool(device, pool, null);
            pool = 0L;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int count = sets.length;
            VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
            sizes.get(0).type(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2 * count);
            sizes.get(1).type(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(2 * count);
            VkDescriptorPoolCreateInfo pi = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(count).pPoolSizes(sizes);
            LongBuffer p = stack.mallocLong(1);
            RtFailure.check(VK10.vkCreateDescriptorPool(device, pi, null, p), "create_bloom_pool");
            pool = p.get(0);
            LongBuffer layouts = stack.mallocLong(count);
            for (int i = 0; i < count; i++) {
                layouts.put(i, setLayout);
            }
            VkDescriptorSetAllocateInfo ai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(pool)
                .pSetLayouts(layouts);
            LongBuffer out = stack.mallocLong(count);
            RtFailure.check(VK10.vkAllocateDescriptorSets(device, ai, out), "allocate_bloom_sets");
            for (int i = 0; i < count; i++) {
                sets[i] = out.get(i);
            }
            // set 0: output -> mip0 ; 1..L-1: mip(i-1) -> mip i ; L..2L-2: upsample mip(i+1) -> mip i (i = L-2..0) ; 2L-1: apply (mip0 -> output)
            write(stack, sets[0], outputView, mips[0].view, outputView, depthView);
            for (int i = 1; i < LEVELS; i++) {
                write(stack, sets[i], mips[i - 1].view, mips[i].view, outputView, depthView);
            }
            for (int i = LEVELS - 2; i >= 0; i--) {
                write(stack, sets[LEVELS + (LEVELS - 2 - i)], mips[i + 1].view, mips[i].view, outputView, depthView);
            }
            write(stack, sets[2 * LEVELS - 1], mips[0].view, mips[0].view, outputView, depthView);
        }
        boundOutput = outputView;
        boundDepth = depthView;
    }

    /**
     * Slang path: only the mips are host-owned; the native core allocates the descriptor set of every dispatch. Old
     * mips are destroyed only after the device is idle and the recordings that reference their views are released.
     */
    private void ensureMips(VkCommandBuffer cmd, RtMemory mem, int width, int height) {
        if (mips[0] != null && w == width && h == height) {
            return;
        }
        VK10.vkDeviceWaitIdle(device);
        releaseRecordings();
        RtMemory old = memory;
        for (int i = 0; i < LEVELS; i++) {
            if (mips[i] != null && old != null) {
                old.destroy(mips[i]);
            }
            mips[i] = mem.createImage("rt_bloom_mip" + i, mipSize(width, i), mipSize(height, i),
                VK10.VK_FORMAT_R16G16B16A16_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            VkBarriers.transition(cmd, mips[i].image, VK10.VK_IMAGE_ASPECT_COLOR_BIT, VK10.VK_IMAGE_LAYOUT_UNDEFINED,
                VK10.VK_IMAGE_LAYOUT_GENERAL);
        }
        memory = mem;
        w = width;
        h = height;
        LOGGER.info("[VKB-ATMO] rt_bloom {}x{} mips={} smallest={}x{} RGBA16F (Slang)", width, height, LEVELS,
            mips[LEVELS - 1].width, mips[LEVELS - 1].height);
    }

    private void write(MemoryStack stack, long set, long srcView, long dstView, long finalView, long depthView) {
        VkWriteDescriptorSet.Buffer wr = VkWriteDescriptorSet.calloc(4, stack);
        sampled(wr.get(0), stack, set, 0, srcView, linearSampler);
        storage(wr.get(1), stack, set, 1, dstView);
        storage(wr.get(2), stack, set, 2, finalView);
        sampled(wr.get(3), stack, set, 3, depthView, nearestSampler);
        VK10.vkUpdateDescriptorSets(device, wr, null);
    }

    private static void sampled(VkWriteDescriptorSet wr, MemoryStack stack, long set, int binding, long view, long sampler) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).sampler(sampler).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        wr.sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(info);
    }

    private static void storage(VkWriteDescriptorSet wr, MemoryStack stack, long set, int binding, long view) {
        VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack);
        info.get(0).imageView(view).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL);
        wr.sType$Default().dstSet(set).dstBinding(binding).descriptorCount(1)
            .descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(info);
    }

    /**
     * Records the chain and the apply dispatch. With {@code bloom == false} only the sun glare is applied (the chain is skipped and
     * the bloom strength pushed as 0). {@code output} must be in GENERAL and hold the final image.
     */
    void record(VkCommandBuffer cmd, RtMemory mem, int width, int height, long outputView, long depthView, Params pr, boolean bloom) {
        ensure(cmd, mem, width, height, outputView, depthView);
        if (slang()) {
            recordSlang(cmd, width, height, outputView, depthView, pr, bloom);
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pc = stack.malloc(PUSH_BYTES);
            fillPushConstants(pc, pr, bloom);
            if (bloom) {
                dispatch(cmd, 0, sets[0], pc, mips[0].width, mips[0].height, true);
                for (int i = 1; i < LEVELS; i++) {
                    dispatch(cmd, 1, sets[i], pc, mips[i].width, mips[i].height, true);
                }
                for (int i = LEVELS - 2; i >= 0; i--) {
                    dispatch(cmd, 2, sets[LEVELS + (LEVELS - 2 - i)], pc, mips[i].width, mips[i].height, true);
                }
            }
            // the apply dispatch is the exit of the pass: its barrier must stay full-scope for the consumers of the output image
            dispatch(cmd, 3, sets[2 * LEVELS - 1], pc, width, height, false);
        }
    }

    /**
     * Slang path: the same 12 dispatches, but every dispatch gets a descriptor set from the native core and is
     * addressed by Slang parameter name. One native recording covers the whole chain; the host releases it through
     * {@link #releaseRecording(long)} once the command buffer completed (or was discarded).
     */
    private void recordSlang(VkCommandBuffer cmd, int width, int height, long outputView, long depthView, Params pr,
                             boolean bloom) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pc = stack.malloc(PUSH_BYTES);
            fillPushConstants(pc, pr, bloom);
            // Track before the first dispatch: if a later one fails, the partially recorded command buffer still has
            // native descriptor pools that must be released on the discard path.
            recordings.add(cmd.address());
            if (bloom) {
                dispatchSlang(slangPrograms[0], cmd, mips[0].width, mips[0].height, pc,
                    List.of(SlangProgram.Resource.sampled("srcTex", outputView, linearSampler),
                        SlangProgram.Resource.storageImage("dstImg", mips[0].view),
                        SlangProgram.Resource.sampled("depthTex", depthView, nearestSampler)));
                VkBarriers.computeToCompute(cmd);
                for (int i = 1; i < LEVELS; i++) {
                    dispatchSlang(slangPrograms[1], cmd, mips[i].width, mips[i].height, pc,
                        List.of(SlangProgram.Resource.sampled("srcTex", mips[i - 1].view, linearSampler),
                            SlangProgram.Resource.storageImage("dstImg", mips[i].view)));
                    VkBarriers.computeToCompute(cmd);
                }
                for (int i = LEVELS - 2; i >= 0; i--) {
                    dispatchSlang(slangPrograms[2], cmd, mips[i].width, mips[i].height, pc,
                        List.of(SlangProgram.Resource.sampled("srcTex", mips[i + 1].view, linearSampler),
                            SlangProgram.Resource.storageImage("dstImg", mips[i].view)));
                    VkBarriers.computeToCompute(cmd);
                }
            }
            // the apply dispatch is the exit of the pass: its barrier must stay full-scope for the consumers of the output image
            dispatchSlang(slangPrograms[3], cmd, width, height, pc,
                List.of(SlangProgram.Resource.sampled("srcTex", mips[0].view, linearSampler),
                    SlangProgram.Resource.storageImage("finalImg", outputView),
                    SlangProgram.Resource.sampled("depthTex", depthView, nearestSampler)));
            VkBarriers.fullMemoryBarrier(cmd);
        }
    }

    /** The 128-byte block of the legacy push constant range; both implementations read it at the same offsets. */
    private static void fillPushConstants(ByteBuffer pc, Params pr, boolean bloom) {
        pc.clear();
        pr.invViewProj.get(0, pc);
        pc.putFloat(64, pr.sunDir[0]).putFloat(68, pr.sunDir[1]).putFloat(72, pr.sunDir[2]).putFloat(76, pr.glareStrength);
        pc.putFloat(80, pr.camX).putFloat(84, pr.camY).putFloat(88, pr.camZ).putFloat(92, pr.flipY ? 1f : 0f);
        pc.putFloat(96, pr.threshold).putFloat(100, pr.knee).putFloat(104, bloom ? pr.strength : 0f);
        pc.putFloat(112, pr.tonemapped ? 1f : 0f).putFloat(116, pr.scatter).putFloat(120, pr.sunNdcX).putFloat(124, pr.sunNdcY);
    }

    private static void dispatchSlang(SlangProgram program, VkCommandBuffer cmd, int gx, int gy, ByteBuffer pc,
                                      List<SlangProgram.Resource> resources) {
        program.dispatch(cmd.address(), program.groups(gx, 0), program.groups(gy, 1), 1, resources, pc);
    }

    /**
     * One chain dispatch. Every mip lives permanently in GENERAL layout, so between chain stages only ordering is
     * needed: with {@code computeToCompute} true, the barrier is COMPUTE->COMPUTE instead of the ALL_COMMANDS
     * sledgehammer, which would also stall the transfer / graphics / AS work of the frame (11 such barriers per frame).
     */
    private void dispatch(VkCommandBuffer cmd, int stage, long set, ByteBuffer pc, int gx, int gy, boolean computeToCompute) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VK10.vkCmdBindPipeline(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[stage]);
            VK10.vkCmdBindDescriptorSets(cmd, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(set), null);
            VK10.vkCmdPushConstants(cmd, pipelineLayout, VK10.VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            VK10.vkCmdDispatch(cmd, (gx + 7) / 8, (gy + 7) / 8, 1);
        }
        if (computeToCompute) {
            VkBarriers.computeToCompute(cmd);
        } else {
            VkBarriers.fullMemoryBarrier(cmd);
        }
    }

    void destroy() {
        VK10.vkDeviceWaitIdle(device);
        // The device is idle, so every recording (including one of a discarded frame) can be released here; the
        // runtime refuses to shut down while any recording is still registered.
        releaseRecordings();
        RtMemory mem = memory;
        for (int i = 0; i < LEVELS; i++) {
            if (mips[i] != null) {
                if (mem != null) {
                    mem.destroy(mips[i]);
                }
                mips[i] = null;
            }
        }
        memory = null;
        if (!slang()) {
            if (pool != 0L) {
                VK10.vkDestroyDescriptorPool(device, pool, null);
                pool = 0L;
            }
            for (int s = 0; s < STAGES; s++) {
                VK10.vkDestroyPipeline(device, pipelines[s], null);
                VK10.vkDestroyShaderModule(device, shaderModules[s], null);
            }
            VK10.vkDestroyPipelineLayout(device, pipelineLayout, null);
            VK10.vkDestroyDescriptorSetLayout(device, setLayout, null);
        }
        VK10.vkDestroySampler(device, linearSampler, null);
        VK10.vkDestroySampler(device, nearestSampler, null);
    }

    /**
     * Host calls this once the GPU finished (or the recording was discarded) for {@code commandBuffer}. The native
     * core keeps one descriptor pool per recording, so an unreleased recording pins it and refuses core shutdown.
     */
    void releaseRecording(long commandBuffer) {
        if (!slang() || !recordings.contains(commandBuffer)) {
            return;
        }
        slangRuntime.releaseRecording(commandBuffer);
        recordings.remove(commandBuffer);
    }

    private void releaseRecordings() {
        if (!slang()) {
            return;
        }
        for (Long commandBuffer : List.copyOf(recordings)) {
            releaseRecording(commandBuffer);
        }
    }
}
