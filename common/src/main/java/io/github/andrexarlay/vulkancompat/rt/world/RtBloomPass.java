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
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
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
 * <p>The AOT Slang programs ({@code passes/bloom_*.slang}) are mandatory. Mips and samplers remain host-owned
 * until the native renderer resource owner replaces this adapter.
 */
final class RtBloomPass {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    static final int LEVELS = 6;
    static final int PUSH_BYTES = 128;
    private static final int STAGES = 4;
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
    /** Four AOT programs in stage order. */
    private final SlangProgram[] slangPrograms;
    private final long linearSampler;
    private final long nearestSampler;
    /** Command buffers with live native recordings; released before the handle is reused or the mips are recreated. */
    private final Set<Long> recordings = new LinkedHashSet<>();
    private RtMemory memory;
    private final RtMemory.Img[] mips = new RtMemory.Img[LEVELS];
    private int w;
    private int h;

    RtBloomPass(VkDevice device, SlangRuntime slangRuntime) {
        this.device = device;
        this.slangRuntime = slangRuntime;
        this.slangPrograms = trySlang(slangRuntime);
        if (slangPrograms == null) {
            throw new RtFailure("slang_bloom", "AOT Slang bloom programs are unavailable or incompatible");
        }
        long createdLinear = 0L;
        long createdNearest = 0L;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            VkSamplerCreateInfo si = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR).mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).minLod(0f).maxLod(0f);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "create_bloom_sampler");
            createdLinear = p.get(0);
            si.magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST);
            RtFailure.check(VK10.vkCreateSampler(device, si, null, p), "create_bloom_sampler_nearest");
            createdNearest = p.get(0);
        } catch (RuntimeException e) {
            if (createdNearest != 0L) VK10.vkDestroySampler(device, createdNearest, null);
            if (createdLinear != 0L) VK10.vkDestroySampler(device, createdLinear, null);
            throw e;
        }
        linearSampler = createdLinear;
        nearestSampler = createdNearest;
        LOGGER.info("[VKB-ATMO] bloom on AOT Slang passes {}", String.join(", ", SLANG_NAMES));
    }

    /**
     * Registers the four AOT programs and checks their generated manifests against the resources this class can supply.
     * Returns {@code null} on any mismatch so the caller can report the missing
     * path instead of dispatching with a wrong layout.
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
            LOGGER.warn("[VKB-ATMO] bloom Slang path unavailable: {}", e.getMessage());
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

    /** Size of mip {@code i} (0 = half resolution) of a {@code size}-wide image. */
    static int mipSize(int size, int i) {
        return Math.max(1, (size + (1 << (i + 1)) - 1) >> (i + 1));
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

    /**
     * Records the chain and the apply dispatch. With {@code bloom == false} only the sun glare is applied (the chain is skipped and
     * the bloom strength pushed as 0). {@code output} must be in GENERAL and hold the final image.
     */
    void record(VkCommandBuffer cmd, RtMemory mem, int width, int height, long outputView, long depthView, Params pr, boolean bloom) {
        ensureMips(cmd, mem, width, height);
        recordSlang(cmd, width, height, outputView, depthView, pr, bloom);
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
        VK10.vkDestroySampler(device, linearSampler, null);
        VK10.vkDestroySampler(device, nearestSampler, null);
    }

    /**
     * Host calls this once the GPU finished (or the recording was discarded) for {@code commandBuffer}. The native
     * core keeps one descriptor pool per recording, so an unreleased recording pins it and refuses core shutdown.
     */
    void releaseRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) {
            return;
        }
        slangRuntime.releaseRecording(commandBuffer);
        recordings.remove(commandBuffer);
    }

    private void releaseRecordings() {
        for (Long commandBuffer : List.copyOf(recordings)) {
            releaseRecording(commandBuffer);
        }
    }
}
