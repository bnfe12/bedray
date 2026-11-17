package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayVolumetricNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.Set;

/** Host settings/POD adapter; native owns the two-stage pass and temporal resource lifetime. */
final class RtSlangVolumetricPass implements AutoCloseable {
    private final SlangRuntime runtime;
    private final BedrayVolumetricNative pass;
    private final RtPathTraceSlangPass.Frame frame = new RtPathTraceSlangPass.Frame();
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean reset = true, closed;
    private int settingsHash;

    RtSlangVolumetricPass(SlangRuntime runtime) {
        this.runtime = runtime;
        var scene = runtime.program("volumetric_march_scene").orElseThrow();
        var apply = runtime.program("volumetric_apply").orElseThrow();
        require(scene, true); require(apply, false);
        long bytes = runtime.capabilities().deviceMemoryBytes();
        pass = new BedrayVolumetricNative(runtime.library(), runtime.core(), scene.nativeHandle(),
            apply.nativeHandle(), bytes > 0 ? Math.min(256L << 20, bytes / 8) : 128L << 20, 16L << 20);
    }

    private static void require(SlangProgram program, boolean march) {
        var manifest = program.manifest();
        if (!java.util.Arrays.equals(manifest.threadGroupSize(), new int[]{8, 8, 1})
            || manifest.pushConstantBytes() != (march ? BedrayVolumetricNative.MARCH_BYTES : 32)
            || manifest.bindings().size() != (march ? 8 : 4))
            throw new IllegalStateException("invalid volumetric pass manifest: " + program.name());
        String[] names = march ? new String[]{"tlas", "sections", "albedoAtlas", "materialAtlas", "accumImage", "outDepth", "frame", "emitters"}
            : new String[]{"outImage", "accumImage", "outDepth", "frame"};
        int[] indices = march ? new int[]{0, 2, 3, 9, 11, 12, 16, 18} : new int[]{1, 11, 12, 16};
        for (int i = 0; i < names.length; i++) {
            var binding = manifest.binding(names[i]);
            int type = names[i].equals("tlas") ? BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
                : names[i].equals("sections") || names[i].equals("emitters") ? BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER
                : names[i].equals("albedoAtlas") || names[i].equals("materialAtlas") ? BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER
                : names[i].equals("frame") ? BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER
                : BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE;
            if (binding == null || binding.binding() != indices[i] || binding.descriptorType() != type
                || (names[i].equals("sections") && binding.elementBytes() != 64)
                || (names[i].equals("emitters") && binding.elementBytes() != 16)
                || (names[i].equals("frame") && binding.elementBytes() != 608))
                throw new IllegalStateException("invalid volumetric binding: " + names[i]);
        }
    }

    void record(VkCommandBuffer cmd, RtFrameBindings.Views views, RtFrameParams p, int width, int height) {
        record(cmd, views, p, width, height, true);
    }

    /**
     * Whether this frame has a block-light glow to draw: the setting is on, the atmosphere's volumetric fog switch is on, the
     * camera is not under water and the frame carries an emitter list. The caller (RtAtmosphere) uses it to decide whether the
     * pass has to run at all, so a night scene with no shafts and no haze still gets the halos of its torches.
     */
    static boolean glowActive(RtFrameParams p) {
        return RtAtmosphereSettings.emitterGlow() > 0f && RtAtmosphereSettings.emitterGlowDensity() > 0f
            && p.volumetricFogEnabled && !p.cameraInWater && p.emitterCount > 0 && p.emitterHandle != 0L;
    }

    /** {@code shafts} false: only the distance haze is wanted, the march runs with zero strength (adds nothing). */
    void record(VkCommandBuffer cmd, RtFrameBindings.Views views, RtFrameParams p, int width, int height, boolean shafts) {
        if (closed) throw new IllegalStateException("volumetric pass is closed");
        if (views.table() == null) throw new IllegalArgumentException("volumetric pass requires the scene section table");
        int steps = RtAtmosphereSettings.godRaySteps();
        float strength = shafts ? RtAtmosphereSettings.godRayStrength() : 0f, range = RtAtmosphereSettings.godRayRange();
        boolean glow = glowActive(p);
        float glowGain = glow ? RtAtmosphereSettings.emitterGlow() : 0f;
        float glowDensity = RtAtmosphereSettings.emitterGlowDensity();
        int hash = java.util.Objects.hash(steps, strength, range, p.fogDensity, p.fogInScattering, shafts, glowGain, glowDensity);
        RtSlangFrameData.fill(frame, p, width, height, 0, 0);
        if (!recordings.add(cmd.address())) throw new IllegalStateException("volumetric recording still pending");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var constants = stack.malloc(BedrayVolumetricNative.FRAME_CONSTANTS_BYTES);
            var march = stack.calloc(BedrayVolumetricNative.MARCH_BYTES);
            var apply = stack.calloc(BedrayVolumetricNative.APPLY_BYTES);
            frame.writeTo(constants);
            float aerialSigma = RtAerialFog.sigma(RtAtmosphereSettings.fogAerial(), renderDistanceBlocks(), rainOf(p), p.key.dir[1],
                p.cameraInWater);
            fillPush(march, apply, p, steps, strength, range, aerialSigma,
                RtAerialFog.pack(RtAerialFog.packedColor(p.skyColor, p.key.color, p.key.dir[1]), p.cameraY),
                glowGain, glowDensity);
            int flags = BedrayVolumetricNative.NATIVE_SCENE
                | (reset || settingsHash != hash ? BedrayVolumetricNative.RESET_HISTORY : 0);
            // The emitter list is the segment of the RtEmitters ring this frame's path trace reads; without one the native pass
            // substitutes the section table and the glow strength above is already zero.
            long emitterBuffer = glow ? p.emitterHandle : 0L;
            long emitterOffset = glow ? p.emitterOffset : 0L;
            long emitterBytes = glow ? EmitterTable.bytes(p.emitterCount) : 0L;
            pass.record(cmd.address(), views.output(), views.depth(), views.tlas(), views.table().handle(), views.table().bytes(),
                views.atlas(), views.material(), runtime.atlasSampler(), emitterBuffer, emitterOffset, emitterBytes,
                width, height, flags, p.frame, constants, march, apply);
        }
        reset = false; settingsHash = hash;
    }

    private static float rainOf(RtFrameParams p) {
        return Math.max(0f, Math.min(1f, 1f - p.key.bodyAlpha));
    }

    private static float renderDistanceBlocks() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        return mc != null && mc.options != null ? mc.options.getEffectiveRenderDistance() * 16f : 256f;
    }

    static void fillPush(ByteBuffer march, ByteBuffer apply, RtFrameParams p, int steps, float strength, float range) {
        fillPush(march, apply, p, steps, strength, range, 0f, 0);
    }

    /** {@code aerialSigma} / {@code aerialColor}: distance haze of {@link RtAerialFog}, the two words that were padding. */
    static void fillPush(ByteBuffer march, ByteBuffer apply, RtFrameParams p, int steps, float strength, float range,
                         float aerialSigma, int aerialColor) {
        fillPush(march, apply, p, steps, strength, range, aerialSigma, aerialColor, 0f, 0f);
    }

    /**
     * {@code glow} / {@code glowDensity}: block-light glow strength (0 = off) and air density multiplier; the camera offset of
     * the emitter list rides next to them, words the push block gained when it grew from 96 to 112 bytes.
     */
    static void fillPush(ByteBuffer march, ByteBuffer apply, RtFrameParams p, int steps, float strength, float range,
                         float aerialSigma, int aerialColor, float glow, float glowDensity) {
        // The shafts are added to the tone-mapped picture, so they are scaled by the exposure that picture was made with:
        // the frame's own number, which the display step reads too.
        float exposure = p.exposure;
        for (int i = 0; i < 3; i++) {
            march.putFloat(i * 4, p.key.dir[i]); march.putFloat(16 + i * 4, p.key.color[i]);
        }
        march.putFloat(12, strength).putFloat(28, range);
        march.putFloat(32, p.cameraDeltaX).putFloat(36, p.cameraDeltaY).putFloat(40, p.cameraDeltaZ);
        march.putFloat(44, Math.max(0f, Math.min(1f, 1f - p.key.bodyAlpha)));
        march.putFloat(48, exposure).putFloat(52, p.fogInScattering).putFloat(56, p.fogDensity);
        march.putFloat(60, (float) p.cameraY).putFloat(64, 0.01f).putInt(68, steps);
        march.putInt(72, p.frame).putInt(76, 0).putInt(80, p.flipY ? 1 : 0).putInt(84, 1);
        march.putFloat(88, Float.isFinite(glowDensity) ? Math.max(0f, glowDensity) : 0f).putInt(92, 0);
        march.putFloat(96, p.emitterCamRelX).putFloat(100, p.emitterCamRelY).putFloat(104, p.emitterCamRelZ);
        march.putFloat(108, Float.isFinite(glow) ? Math.max(0f, glow) : 0f);
        apply.putFloat(0, exposure).putFloat(4, range).putInt(8, 1).putInt(12, p.frame)
            .putInt(16, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.tonemapper().ordinal())
            .putFloat(20, Float.isFinite(aerialSigma) ? Math.max(0f, aerialSigma) : 0f).putInt(24, aerialColor).putInt(28, 0);
    }

    void resetHistory() { reset = true; }
    void releaseRecording(long command) {
        if (!recordings.contains(command)) return;
        runtime.releaseRecording(command); recordings.remove(command);
    }
    void discardRecording(long command) {
        if (!recordings.contains(command)) return;
        runtime.discardRecording(command); recordings.remove(command); reset = true;
    }
    @Override public void close() {
        if (closed) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("volumetric recordings require completion/discard");
        pass.close(); closed = true;
    }
}
