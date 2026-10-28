package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayBloomNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangPassManifest;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Bloom and sun glare on the final image ({@code rt_bloom.comp}, see its header): prefilter + 13 tap downsample chain of
 * {@link #LEVELS} RGBA16F mips, in-place scatter-weighted upsample, then one apply dispatch that adds the glow (and the optional sun
 * halo) before the tone map shoulder. Like {@link RtVolumetricPass} it only reads and writes the final image after the denoiser, so
 * no guide buffer of NRD / DLSS Ray Reconstruction is touched. All sizes derive from the image size, so the glow radius scales with
 * the render resolution and works the same with an upscaler (applied at render resolution, before the upscale).
 *
 * <p>The AOT Slang programs ({@code passes/bloom_*.slang}) are mandatory. C++ owns the complete chain and its mip generations; this adapter supplies Minecraft frame parameters.
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

    private final SlangRuntime slangRuntime;
    private final BedrayBloomNative nativePass;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean destroyed;

    RtBloomPass(SlangRuntime runtime) {
        SlangProgram[] programs = trySlang(runtime);
        if (programs == null) throw new RtFailure("slang_bloom", "AOT Slang bloom programs are unavailable or incompatible");
        long bytes = runtime.capabilities().deviceMemoryBytes();
        nativePass = new BedrayBloomNative(runtime.library(), runtime.core(),
            java.util.Arrays.stream(programs).mapToLong(SlangProgram::nativeHandle).toArray(),
            runtime.linearSampler(), runtime.atlasSampler(), bytes > 0 ? Math.min(512L << 20, bytes / 8) : 256L << 20);
        slangRuntime = runtime;
        LOGGER.info("[VKB-ATMO] bloom on native coarse Slang pass {}", String.join(", ", SLANG_NAMES));
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
        if (!java.util.Arrays.equals(manifest.threadGroupSize(), new int[]{8, 8, 1}))
            throw new IllegalStateException(program.name() + " must use the native bloom 8x8x1 group contract");
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

    /** Records one coarse native call. On failure the whole host recording must be discarded. */
    void record(VkCommandBuffer cmd, int width, int height, long outputView, long depthView, Params params, boolean bloom) {
        if (destroyed) throw new IllegalStateException("Bloom pass is destroyed");
        if (!recordings.add(cmd.address())) throw new IllegalStateException("bloom recording still pending");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES);
            fillPushConstants(push, params, bloom);
            nativePass.record(cmd.address(), width, height, outputView, depthView, push, bloom);
        }
    }

    /** The 128-byte block of the legacy push constant range; both implementations read it at the same offsets. */
    private static void fillPushConstants(ByteBuffer pc, Params pr, boolean bloom) {
        pc.clear();
        pr.invViewProj.get(0, pc);
        pc.putFloat(64, pr.sunDir[0]).putFloat(68, pr.sunDir[1]).putFloat(72, pr.sunDir[2]).putFloat(76, pr.glareStrength);
        pc.putFloat(80, pr.camX).putFloat(84, pr.camY).putFloat(88, pr.camZ).putFloat(92, pr.flipY ? 1f : 0f);
        pc.putFloat(96, pr.threshold).putFloat(100, pr.knee).putFloat(104, bloom ? pr.strength : 0f).putFloat(108, 0f);
        pc.putFloat(112, pr.tonemapped ? 1f : 0f).putFloat(116, pr.scatter).putFloat(120, pr.sunNdcX).putFloat(124, pr.sunNdcY);
    }

    /** Host drained submissions; the exact host tickets must already have released this pass. */
    void destroy() {
        if (destroyed) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("bloom recordings require completion/discard");
        nativePass.close();
        destroyed = true;
    }

    void releaseRecording(long command) {
        if (!recordings.contains(command)) return;
        slangRuntime.releaseRecording(command);
        recordings.remove(command);
    }

    void discardRecording(long command) {
        if (!recordings.contains(command)) return;
        slangRuntime.discardRecording(command);
        recordings.remove(command);
    }
}
