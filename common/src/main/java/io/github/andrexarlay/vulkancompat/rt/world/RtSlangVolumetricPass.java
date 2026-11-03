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
        var legacy = runtime.program("volumetric_march").orElseThrow();
        var scene = runtime.program("volumetric_march_scene").orElseThrow();
        var apply = runtime.program("volumetric_apply").orElseThrow();
        require(legacy, true); require(scene, true); require(apply, false);
        long bytes = runtime.capabilities().deviceMemoryBytes();
        pass = new BedrayVolumetricNative(runtime.library(), runtime.core(), legacy.nativeHandle(), scene.nativeHandle(),
            apply.nativeHandle(), bytes > 0 ? Math.min(256L << 20, bytes / 8) : 128L << 20, 16L << 20);
    }

    private static void require(SlangProgram program, boolean march) {
        var manifest = program.manifest();
        if (!java.util.Arrays.equals(manifest.threadGroupSize(), new int[]{8, 8, 1})
            || manifest.pushConstantBytes() != (march ? 96 : 24) || manifest.bindings().size() != (march ? 5 : 4))
            throw new IllegalStateException("invalid volumetric pass manifest: " + program.name());
        String[] names = march ? new String[]{"tlas", "sections", "accumImage", "outDepth", "frame"}
            : new String[]{"outImage", "accumImage", "outDepth", "frame"};
        int[] indices = march ? new int[]{0, 2, 11, 12, 16} : new int[]{1, 11, 12, 16};
        for (int i = 0; i < names.length; i++) {
            var binding = manifest.binding(names[i]);
            int type = names[i].equals("tlas") ? BedrayCoreLayouts.DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR
                : names[i].equals("sections") ? BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_BUFFER
                : names[i].equals("frame") ? BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER
                : BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE;
            if (binding == null || binding.binding() != indices[i] || binding.descriptorType() != type
                || (names[i].equals("sections") && binding.elementBytes() != 64)
                || (names[i].equals("frame") && binding.elementBytes() != 608))
                throw new IllegalStateException("invalid volumetric binding: " + names[i]);
        }
    }

    void record(VkCommandBuffer cmd, RtFrameBindings.Views views, RtFrameParams p, int width, int height) {
        if (closed) throw new IllegalStateException("volumetric pass is closed");
        if (views.table() == null) throw new IllegalArgumentException("volumetric pass requires the scene section table");
        int steps = RtAtmosphereSettings.godRaySteps();
        float strength = RtAtmosphereSettings.godRayStrength(), range = RtAtmosphereSettings.godRayRange();
        int hash = java.util.Objects.hash(steps, strength, range, p.fogDensity, p.fogInScattering);
        RtSlangFrameData.fill(frame, p, width, height, 0, 0);
        if (!recordings.add(cmd.address())) throw new IllegalStateException("volumetric recording still pending");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var constants = stack.malloc(BedrayVolumetricNative.FRAME_CONSTANTS_BYTES);
            var march = stack.calloc(BedrayVolumetricNative.MARCH_BYTES);
            var apply = stack.calloc(BedrayVolumetricNative.APPLY_BYTES);
            frame.writeTo(constants);
            fillPush(march, apply, p, steps, strength, range);
            int flags = (views.table().sceneOwned ? BedrayVolumetricNative.NATIVE_SCENE : 0)
                | (reset || settingsHash != hash ? BedrayVolumetricNative.RESET_HISTORY : 0);
            pass.record(cmd.address(), views.output(), views.depth(), views.tlas(), views.table().handle, views.table().size,
                width, height, flags, p.frame, constants, march, apply);
        }
        reset = false; settingsHash = hash;
    }

    static void fillPush(ByteBuffer march, ByteBuffer apply, RtFrameParams p, int steps, float strength, float range) {
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
        march.putInt(88, 0).putInt(92, 0);
        apply.putFloat(0, exposure).putFloat(4, range).putInt(8, 1).putInt(12, p.frame).putInt(16, 0).putInt(20, 0);
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
