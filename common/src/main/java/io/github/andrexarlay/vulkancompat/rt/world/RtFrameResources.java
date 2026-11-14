package io.github.andrexarlay.vulkancompat.rt.world;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/** Owns frame output images and per-pixel motion storage; callers only borrow their handles. */
final class RtFrameResources implements AutoCloseable {
    interface Allocator {
        RtMemory.Img image(String name, int width, int height, int format, int usage);
        RtMemory.Buf motion(long bytes);
        void destroy(RtMemory.Img image);
        void destroy(RtMemory.Buf buffer);
    }

    private final Allocator allocator;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private final RtMemory.Img[] abandoned = new RtMemory.Img[6];
    private RtMemory.Img output, depth, composite, resolvedDepth, localLum, postHdr;
    private RtMemory.Buf motion;
    private RtMemory.Buf abandonedMotion;
    private boolean initialized;
    private long initializer, generation;

    RtFrameResources(RtMemory memory) {
        this(new Allocator() {
            public RtMemory.Img image(String name, int w, int h, int format, int usage) {
                return memory.createImage(name, w, h, format, usage);
            }
            public RtMemory.Buf motion(long bytes) {
                return memory.createBuffer("entity_motion_px", bytes,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, false);
            }
            public void destroy(RtMemory.Img image) { memory.destroy(image); }
            public void destroy(RtMemory.Buf buffer) { memory.destroy(buffer); }
        });
    }

    RtFrameResources(Allocator allocator) { this.allocator = java.util.Objects.requireNonNull(allocator); }
    RtMemory.Img output() { return output; }
    RtMemory.Img depth() { return depth; }
    RtMemory.Img composite() { return composite; }
    /** Block-mean log luminance of the presented HDR, one texel per LOCAL_TONEMAP_BLOCK pixels (RtLocalTonemap). */
    RtMemory.Img localLum() { return localLum; }
    RtMemory.Img postHdr() { return postHdr; }
    static final int LOCAL_TONEMAP_BLOCK = 16;
    RtMemory.Img displayDepth() { return resolvedDepth != null ? resolvedDepth : depth; }
    RtMemory.Buf motion() { return motion; }
    boolean initializationPending() { return initializer != 0; }
    boolean needsOutput(int w, int h) { return needsOutput(w, h, w, h, false); }
    boolean needsOutput(int w, int h, int displayW, int displayH) {
        return needsOutput(w, h, displayW, displayH, true);
    }
    private boolean needsOutput(int w, int h, int displayW, int displayH, boolean resolveDepth) {
        return output == null || output.width != w || output.height != h || composite == null
            || composite.width != displayW || composite.height != displayH || (resolvedDepth != null) != resolveDepth;
    }
    boolean needsMotion(int w, int h) { return motion == null || motion.size < motionBytes(w, h); }

    void ensureOutput(int w, int h) {
        ensureOutput(w, h, w, h, false);
    }

    void ensureOutput(int w, int h, int displayW, int displayH) {
        ensureOutput(w, h, displayW, displayH, true);
    }

    private void ensureOutput(int w, int h, int displayW, int displayH, boolean resolveDepth) {
        requireExtent(w, h);
        requireExtent(displayW, displayH);
        if (!needsOutput(w, h, displayW, displayH, resolveDepth)) return;
        requireUnreferenced("replace_output");
        collectAbandoned();
        RtMemory.Img[] next = new RtMemory.Img[6];
        try {
            next[0] = allocator.image("rt_output", w, h, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            next[1] = allocator.image("rt_depth", w, h, VK10.VK_FORMAT_R32_SFLOAT,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            next[2] = allocator.image("rt_composite", displayW, displayH, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
            if (resolveDepth) next[3] = allocator.image("rt_display_depth", displayW, displayH,
                VK10.VK_FORMAT_R32_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            next[4] = allocator.image("rt_local_lum", (displayW + LOCAL_TONEMAP_BLOCK - 1) / LOCAL_TONEMAP_BLOCK,
                (displayH + LOCAL_TONEMAP_BLOCK - 1) / LOCAL_TONEMAP_BLOCK, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT);
            next[5] = allocator.image("rt_post_hdr", w, h, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                VK10.VK_IMAGE_USAGE_STORAGE_BIT | VK10.VK_IMAGE_USAGE_SAMPLED_BIT);
            destroyOutputs();
        } catch (RuntimeException | Error failure) {
            // Reserve every rollback owner before cleanup can itself fail or allocate an exception.
            System.arraycopy(next, 0, abandoned, 0, next.length);
            for (int i = next.length - 1; i >= 0; --i) {
                if (next[i] == null) continue;
                try { allocator.destroy(next[i]); abandoned[i] = null; }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        output = next[0]; depth = next[1]; composite = next[2]; resolvedDepth = next[3]; localLum = next[4];
        postHdr = next[5];
        initialized = false;
        initializer = 0;
        generation++;
    }

    void ensureMotion(int w, int h) {
        long bytes = motionBytes(w, h);
        if (motion != null && motion.size >= bytes) return;
        requireUnreferenced("replace_motion");
        collectAbandoned();
        RtMemory.Buf next = java.util.Objects.requireNonNull(allocator.motion(bytes), "motion allocation returned null");
        try {
            if (motion != null) allocator.destroy(motion);
        } catch (RuntimeException | Error failure) {
            abandonedMotion = next;
            try { allocator.destroy(next); abandonedMotion = null; }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        motion = next;
    }

    void requireMotion(int w, int h) {
        if (needsMotion(w, h)) throw new IllegalStateException(context("motion_not_prepared"));
    }

    /** Pin before the first transition/write; a second CB must not depend on unproven initialization. */
    void prepare(long command, Consumer<RtMemory.Img> transition) {
        if (command == 0 || output == null) throw new IllegalStateException(context("unprepared_recording"));
        if (initializer != 0 && initializer != command)
            throw new IllegalStateException(context("initializer_pending"));
        recordings.add(command);
        if (!initialized) {
            initializer = command;
            transition.accept(output);
            transition.accept(depth);
            transition.accept(composite);
            if (resolvedDepth != null) transition.accept(resolvedDepth);
            if (localLum != null) transition.accept(localLum);
            if (postHdr != null) transition.accept(postHdr);
            initialized = true;
        }
    }

    void releaseRecording(long command) {
        recordings.remove(command);
        if (initializer == command) initializer = 0;
    }

    void discardRecording(long command) {
        recordings.remove(command);
        if (initializer == command) { initializer = 0; initialized = false; }
    }

    private void requireUnreferenced(String stage) {
        if (!recordings.isEmpty()) throw new IllegalStateException(context(stage));
    }

    private String context(String stage) {
        return "frame_resources stage=" + stage + " generation=" + generation + " extent="
            + (output != null ? output.width + "x" + output.height : "none") + " initializer=0x"
            + Long.toHexString(initializer) + " pendingCommands="
            + recordings.stream().map(value -> "0x" + Long.toHexString(value)).toList();
    }

    private void collectAbandoned() {
        RuntimeException failures = null;
        for (int i = 0; i < abandoned.length; ++i) {
            if (abandoned[i] == null) continue;
            try { allocator.destroy(abandoned[i]); abandoned[i] = null; }
            catch (RuntimeException | Error failure) {
                if (failures == null) failures = new IllegalStateException(context("rollback_cleanup"));
                failures.addSuppressed(failure);
            }
        }
        if (abandonedMotion != null) {
            try { allocator.destroy(abandonedMotion); abandonedMotion = null; }
            catch (RuntimeException | Error failure) {
                if (failures == null) failures = new IllegalStateException(context("rollback_cleanup_motion"));
                failures.addSuppressed(failure);
            }
        }
        if (failures != null) throw failures;
    }

    private void destroyOutputs() {
        if (output != null) { allocator.destroy(output); output = null; }
        if (depth != null) { allocator.destroy(depth); depth = null; }
        if (composite != null) { allocator.destroy(composite); composite = null; }
        if (resolvedDepth != null) { allocator.destroy(resolvedDepth); resolvedDepth = null; }
        if (localLum != null) { allocator.destroy(localLum); localLum = null; }
        if (postHdr != null) { allocator.destroy(postHdr); postHdr = null; }
        initialized = false;
        initializer = 0;
    }

    @Override public void close() {
        requireUnreferenced("close");
        collectAbandoned();
        destroyOutputs();
        if (motion != null) { allocator.destroy(motion); motion = null; }
    }

    private static void requireExtent(int w, int h) {
        if (w <= 0 || h <= 0 || (long) w * h > Integer.MAX_VALUE)
            throw new IllegalArgumentException("invalid frame resource extent=" + w + "x" + h);
    }
    private static long motionBytes(int w, int h) { requireExtent(w, h); return (long) w * h * 16L; }
}
