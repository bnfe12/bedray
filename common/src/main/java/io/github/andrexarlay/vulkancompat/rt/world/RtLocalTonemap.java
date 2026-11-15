package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Local tone mapping of the DLSS-RR result (passes/local_tonemap_lum.slang, passes/local_tonemap_apply.slang): rewrites
 * the presented image from the exposed HDR the denoiser resolved, with the bright local base pulled towards a pivot so
 * a torch pool keeps its texture. RTX Remix Minecraft ships the same kind of operator (rtx.localtonemap.*). The images
 * belong to {@link RtFrameResources}; the HDR source is the denoiser's resolved output.
 *
 * <p>{@code -Dvkrtx.rt.localTonemap=0..2} (highlight strength, 0 = off, default 1) and
 * {@code -Dvkrtx.rt.localTonemap.shadows=0..2} (default 1).
 */
public final class RtLocalTonemap {
    static final int PUSH_BYTES = 48;
    /** Exposed luminance the operator leaves unchanged: the knee of the global curve (TM_KNEE 0.6 / TM_GAIN 1.2). */
    static final float PIVOT_LOG2 = -1.0f;

    private final SlangRuntime runtime;
    private final SlangProgram lum;
    private final SlangProgram apply;
    private final Set<Long> recordings = new LinkedHashSet<>();

    RtLocalTonemap(SlangRuntime runtime) {
        this.runtime = runtime;
        lum = runtime.program("local_tonemap_lum").orElseThrow(() -> new IllegalStateException("Missing local_tonemap_lum AOT"));
        apply = runtime.program("local_tonemap_apply").orElseThrow(() -> new IllegalStateException("Missing local_tonemap_apply AOT"));
        if (lum.manifest().pushConstantBytes() != PUSH_BYTES || lum.manifest().bindings().size() != 3
            || apply.manifest().pushConstantBytes() != PUSH_BYTES || apply.manifest().bindings().size() != 4)
            throw new IllegalStateException("Invalid local tone map manifests");
    }

    /** Session override used by the look scenarios. Strengths are 0..2. */
    public static void setStrength(float highlightStrength, float shadowStrength) {
        RtAtmosphereSettings.set("local_highlights", Float.isFinite(highlightStrength) ? highlightStrength : 1f);
        RtAtmosphereSettings.set("local_shadows", Float.isFinite(shadowStrength) ? shadowStrength : 1f);
    }

    static boolean enabled() {
        return RtAtmosphereSettings.localHighlights() > 0f || RtAtmosphereSettings.localShadows() > 0f;
    }

    /** Overwrites {@code output} from {@code hdr} (same size); {@code exposure} is the factor the compose pass applied. */
    void record(VkCommandBuffer command, RtMemory.Img hdr, RtMemory.Img output, RtMemory.Img blockLum, RtMemory.Img depth,
                float exposure, int frameIndex) {
        if (hdr.width != output.width || hdr.height != output.height)
            throw new IllegalStateException("local tone map: HDR " + hdr.width + "x" + hdr.height + " != output "
                + output.width + "x" + output.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(PUSH_BYTES).order(ByteOrder.nativeOrder());
            push.putInt(0, output.width).putInt(4, output.height).putInt(8, blockLum.width).putInt(12, blockLum.height);
            push.putFloat(16, exposure).putFloat(20, RtAtmosphereSettings.localHighlights())
                .putFloat(24, RtAtmosphereSettings.localShadows()).putFloat(28, PIVOT_LOG2);
            push.putInt(32, io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.tonemapper().ordinal())
                .putInt(36, RtSlangPathPass.postCinemaBits() | RtSlangPathPass.vibrantBit()).putInt(40, frameIndex).putInt(44, 0);
            recordings.add(command.address()); // retain even when native recording fails partway
            lum.dispatch(command.address(), (blockLum.width + 7) / 8, (blockLum.height + 7) / 8, 1,
                List.of(SlangProgram.Resource.storageImage("hdrColor", hdr.view),
                    SlangProgram.Resource.storageImage("blockLum", blockLum.view),
                    SlangProgram.Resource.storageImage("displayDepth", depth.view)), push);
            VkBarriers.fullMemoryBarrier(command);
            apply.dispatch(command.address(), (output.width + 7) / 8, (output.height + 7) / 8, 1,
                List.of(SlangProgram.Resource.storageImage("hdrColor", hdr.view),
                    SlangProgram.Resource.storageImage("blockLum", blockLum.view),
                    SlangProgram.Resource.storageImage("outputColor", output.view),
                    SlangProgram.Resource.storageImage("displayDepth", depth.view)), push);
        }
    }

    void release(long command, boolean discarded) {
        if (!recordings.contains(command)) return;
        if (discarded) runtime.discardRecording(command); else runtime.releaseRecording(command);
        recordings.remove(command);
    }

    void afterHostDrain() { for (long command : List.copyOf(recordings)) release(command, false); }

}
