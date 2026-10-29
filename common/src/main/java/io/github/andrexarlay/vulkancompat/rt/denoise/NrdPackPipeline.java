package io.github.andrexarlay.vulkancompat.rt.denoise;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayNrdPackNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Borrowed game images/settings; native core owns AOT pipelines and immutable descriptors. */
final class NrdPackPipeline {
    static final int BINDINGS = BedrayNrdPackNative.VIEW_COUNT;
    static final int PUSH_BYTES = BedrayNrdPackNative.PUSH_BYTES;
    private static final int[] PACK_BINDINGS = {0,1,2,3,4,5,6,9,10,12,13};
    private static final int[] RR_BINDINGS = {0,1,2,4,5,6,9,10,13};
    private static final int[] COMPOSE_BINDINGS = {0,3,4,7,8,11};
    private final SlangRuntime runtime;
    private final BedrayNrdPackNative nativePass;
    private final Map<String, SlangProgram> programs = new LinkedHashMap<>();
    private final Map<Long, Integer> recordings = new LinkedHashMap<>();
    private long[] views;
    private boolean closed;

    NrdPackPipeline(SlangRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "NRD pack needs the existing SlangRuntime");
        nativePass = new BedrayNrdPackNative(runtime.library(), runtime.core());
        program("nrd_compose", COMPOSE_BINDINGS);
    }

    private SlangProgram program(String name, int[] bindings) {
        return programs.computeIfAbsent(name, ignored -> {
            var result = runtime.program(name).orElseThrow(() -> new IllegalStateException("Missing AOT " + name));
            var manifest = result.manifest();
            if (manifest.pushConstantBytes() != PUSH_BYTES || manifest.bindings().size() != bindings.length
                || !Arrays.equals(manifest.threadGroupSize(), new int[]{8,8,1}))
                throw new IllegalStateException("Invalid NRD pass manifest: " + name);
            for (int binding : bindings) {
                boolean present = manifest.bindings().stream().anyMatch(item -> item.binding() == binding
                    && item.descriptorType() == BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE);
                if (!present) throw new IllegalStateException(name + " missing storage binding " + binding);
            }
            return result;
        });
    }

    /** Copies identities only; previously recorded descriptors remain immutable. */
    void writeViews(long[] nextViews) {
        if (closed) throw new IllegalStateException("NRD pack is closed");
        if (nextViews == null || nextViews.length != BINDINGS) throw new IllegalArgumentException("need 14 NRD views");
        for (long view : nextViews) if (view == 0) throw new IllegalArgumentException("missing NRD view");
        views = nextViews.clone();
    }

    /** Compatibility input; C++ repacks scalar settings into its reflected push. */
    static void writePush(ByteBuffer input, int normalEncoding, int roughnessEncoding, int denoiser, int flags,
                          NrdReblurParams params, float range, int width, int height, float exposure, long motionAddress) {
        input.order(ByteOrder.nativeOrder());
        input.putInt(0, normalEncoding).putInt(4, roughnessEncoding).putInt(8, denoiser).putInt(12, flags);
        input.putFloat(16, params.hitDistA()).putFloat(20, params.hitDistB()).putFloat(24, params.hitDistC()).putFloat(28, range);
        input.putInt(32, width).putInt(36, height).putFloat(40, exposure).putInt(44, 0).putLong(48, motionAddress);
    }

    void recordPack(VkCommandBuffer command, ByteBuffer push, int width, int height) {
        record(command, push, width, height, BedrayNrdPackNative.PACK);
    }
    void recordCompose(VkCommandBuffer command, ByteBuffer push, int width, int height) {
        record(command, push, width, height, BedrayNrdPackNative.COMPOSE);
    }

    private void record(VkCommandBuffer command, ByteBuffer push, int width, int height, int phase) {
        if (closed) throw new IllegalStateException("NRD pack is closed");
        if (command == null || views == null) throw new IllegalStateException("NRD pack is not bound");
        var settings = BedrayNrdPackNative.Settings.fromPush(push, width, height);
        var selected = phase == BedrayNrdPackNative.COMPOSE ? program("nrd_compose", COMPOSE_BINDINGS)
            : settings.denoiser() == 2 ? program("nrd_pack_rr", RR_BINDINGS)
            : program(settings.normal() == 2 ? "nrd_pack_r10" : "nrd_pack_snorm", PACK_BINDINGS);
        long handle = command.address();
        BedrayNrdPackNative.validate(handle, selected.nativeHandle(), views, phase, width, height, settings);
        int previous = recordings.getOrDefault(handle, 0);
        if (phase == BedrayNrdPackNative.PACK ? previous != 0 : previous != 1)
            throw new IllegalStateException("NRD recording phase reused or compose has no pack");
        // A native error may follow partial recording. Keep this identity until
        // the host proves discard/completion; no exception-path release.
        recordings.put(handle, previous | (1 << phase));
        nativePass.record(handle, selected.nativeHandle(), views, phase, width, height, settings);
    }

    void releaseRecording(long command) {
        if (!recordings.containsKey(command)) return;
        runtime.releaseRecording(command);
        recordings.remove(command);
    }
    void discardRecording(long command) {
        if (!recordings.containsKey(command)) return;
        runtime.discardRecording(command);
        recordings.remove(command);
    }
    void releaseRecordingsAfterHostDrain() {
        for (long command : List.copyOf(recordings.keySet())) releaseRecording(command);
    }

    /** Caller proved idle/completion, or this adapter never recorded. */
    void destroy() {
        if (closed) return;
        releaseRecordingsAfterHostDrain();
        // Cached program lifetime belongs to SlangRuntime.
        closed = true;
        views = null;
        programs.clear();
    }
}
