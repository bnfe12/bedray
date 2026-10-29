package io.github.andrexarlay.vulkancompat.rt.world;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayGpuBuffersNative;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayGpuImagesNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.VkBarriers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.ByteBuffer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import static io.github.andrexarlay.vulkancompat.rt.world.RtSlangPathPass.*;

/** Java orchestration retained as the migration's behavioral reference. */
final class ReferencePathTraceBackend implements PathTraceBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    private final SlangRuntime runtime;
    private final RtPathTraceSlangPass trace;
    private final RtSpatialReuseSlangPass spatialReuse;
    private final SlangProgram display;
    private final long sampler;
    private final BedrayGpuImagesNative historyImages;
    private final BedrayGpuBuffersNative nativeBuffers;
    private final BedrayGpuImagesNative.Image[] accum = new BedrayGpuImagesNative.Image[2];
    private final BedrayGpuImagesNative.Image[] historyDepth = new BedrayGpuImagesNative.Image[2];
    private final Set<Long> recordings = new LinkedHashSet<>();
    private BedrayGpuBuffersNative.Buffer reservoirs;
    private int historyParity;
    private int lastWidth, lastHeight;
    private final RestirDiState di = new RestirDiState(false);
    private boolean closed;

    ReferencePathTraceBackend(VkDevice device, SlangRuntime runtime) {
        this.runtime = runtime;
        trace = new RtPathTraceSlangPass(device, runtime);
        BedrayGpuImagesNative nextImages = null;
        BedrayGpuBuffersNative nextBuffers = null;
        try {
            long bytes = runtime.capabilities().deviceMemoryBytes();
            nextImages = runtime.createImageOwner(bytes > 0 ? Math.min(1L << 30, bytes / 8) : 256L << 20);
            nextBuffers = runtime.createBufferOwner(bytes > 0 ? Math.min(1L << 30, bytes / 4) : 512L << 20);
            display = runtime.program(DISPLAY_PASS).orElseThrow();
            requireDisplayManifest(display);
            sampler = runtime.atlasSampler();
            spatialReuse = RtSpatialReuseSlangPass.create(runtime, trace.entityFallback()).orElse(null);
        } catch (RuntimeException | Error failure) {
            if (nextBuffers != null) nextBuffers.close();
            if (nextImages != null) nextImages.close();
            trace.close();
            throw failure;
        }
        historyImages = nextImages;
        nativeBuffers = nextBuffers;
    }

    @Override
    public void prepare(int width, int height) {
        if (closed) throw new IllegalStateException("reference path backend is closed");
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("path extent must be positive");
        prepareReservoirs(width, height);
        if (accum[0] != null && lastWidth == width && lastHeight == height) {
            return;
        }
        BedrayGpuImagesNative.Image[] nextAccum = new BedrayGpuImagesNative.Image[2];
        BedrayGpuImagesNative.Image[] nextDepth = new BedrayGpuImagesNative.Image[2];
        try {
            for (int i = 0; i < 2; ++i) {
                nextAccum[i] = historyImages.create(width, height, VK10.VK_FORMAT_R32G32B32A32_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
                nextDepth[i] = historyImages.create(width, height, VK10.VK_FORMAT_R32_SFLOAT, VK10.VK_IMAGE_USAGE_STORAGE_BIT);
            }
        } catch (RuntimeException | Error e) {
            for (int i = 0; i < 2; ++i) {
                historyImages.retire(nextAccum[i]);
                historyImages.retire(nextDepth[i]);
            }
            throw e;
        }
        for (int i = 0; i < 2; ++i) {
            historyImages.retire(accum[i]);
            historyImages.retire(historyDepth[i]);
            accum[i] = nextAccum[i];
            historyDepth[i] = nextDepth[i];
        }
        historyParity = 0;
        lastWidth = width;
        lastHeight = height;
    }

    /**
     * The reservoir ring of the DI branch: two halves of one 64-byte record per pixel, device-local because the reset is
     * a command-buffer fill. Turning ReSTIR DI off frees it again - the buffer is 250 MB at 1080p and holding it unused
     * would compete with the section arenas.
     */
    private void prepareReservoirs(int width, int height) {
        long pixels = (long) width * height;
        long need = pixels * 2L * RtPathTraceSlangPass.RESERVOIR_BYTES;
        if (!diRequested()) {
            if (reservoirs != null) {
                nativeBuffers.retire(reservoirs);
                reservoirs = null;
            }
            di.reset();
            return;
        }
        if (reservoirs != null && reservoirs.size() >= need) {
            if (di.extentDiffers(width, height)) di.layoutFor(width, height);
            return;
        }
        BedrayGpuBuffersNative.Buffer next = nativeBuffers.createDeviceLocal(need,
            VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        nativeBuffers.retire(reservoirs);
        reservoirs = next;
        di.layoutFor(width, height);
        LOGGER.info("[VKB-RT] Slang ReSTIR DI reservoirs {}x{} x2 = {} MiB", width, height,
            Math.max(1L, need / (1024L * 1024L)));
    }

    @Override public void record(Frame input) {
        if (closed || accum[0] == null) throw new IllegalStateException("reference path is not prepared");
        var cmd = input.command();
        if (!recordings.add(cmd.address())) throw new IllegalStateException("recording still awaits completion/discard");
        var views = input.views();
        var frame = input.constants();
        var dispatch = input.dispatch();
        var emitters = input.emitters();
        var capturedEntities = input.entities();
        int width = frame.width, height = frame.height;
        int accumFrames = input.accumFrames();
        boolean historyWritten = (input.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY) == 0;
        if ((input.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_DI) != 0) di.reset();
        int emitterCount = dispatch.emitterCount();
        int diFlags = 0;
        RtPathTraceSlangPass.Reservoirs reservoirRange = RtPathTraceSlangPass.Reservoirs.NONE;
        if (reservoirs != null) nativeBuffers.retain(cmd.address(), reservoirs);
        if ((dispatch.diFlags() & DI_FLAG_ENABLED) != 0 && emitterCount > 0 && reservoirs != null) {
            if (di.resetPending()) {
                VK10.vkCmdFillBuffer(cmd, reservoirs.handle(), 0L, reservoirs.size(), 0);
                VkBarriers.fullMemoryBarrier(cmd);
                di.ringCleared();
            }
            diFlags = di.flags((dispatch.diFlags() & DI_FLAG_STATIC) != 0, (dispatch.diFlags() & DI_FLAG_FLIP_Y) != 0);
            reservoirRange = new RtPathTraceSlangPass.Reservoirs(reservoirs.handle(), reservoirs.size());
        }
        int diParity = di.parity();
        dispatch = dispatch.withDiState(diFlags, diParity);
        // NRD's IN_MV channel: the frame's own vec4-per-pixel range when the push asked for it, never a stale binding.
        RtPathTraceSlangPass.BufferView motion = (dispatch.flags() & RtPathTraceSlangPass.OPTION_NRD_MOTION) != 0
            && views.motion() != 0L
            ? new RtPathTraceSlangPass.BufferView(views.motion(), 0L,
                (long) width * height * RtPathTraceSlangPass.MOTION_ELEMENT_BYTES)
            : RtPathTraceSlangPass.BufferView.NONE;
        trace.record(cmd, new RtPathTraceSlangPass.Scene(views.tlas(), views.table()),
            new RtPathTraceSlangPass.Atlases(views.atlas(), views.material(), views.normal(), sampler),
            emitters, reservoirRange, capturedEntities, RtPathTraceSlangPass.Guides.of(views.gbuffer()),
            motion, views.depth(), frame, dispatch);
        // Spatial reuse pools the records the trace pass just wrote, in the half it just wrote. Nothing in this frame
        // reads them - the benefit lands in the next frame's temporal import - so the stage owns its own ordering: the
        // barrier between its two phases and a trailing one, both inside record().
        if (spatialReuse != null && (input.flags() & BedrayCoreLayouts.PATH_TRACE_FRAME_SPATIAL) != 0 && diFlags != 0 && emitterCount > 0 && reservoirs != null) {
            spatialReuse.record(cmd, new RtPathTraceSlangPass.Atlases(views.atlas(), views.material(),
                    views.normal(), sampler), emitters, reservoirRange, capturedEntities, trace.frameResource(),
                width, height, frame.frameIndex, diParity, dispatch.emitterCamRelX(), dispatch.emitterCamRelY(),
                dispatch.emitterCamRelZ(), dispatch.maxDistance());
        }

        int flags = displayFlags(input.flags());
        int write = historyParity, read = write ^ 1;
        for (int i = 0; i < 2; ++i) {
            historyImages.retain(cmd.address(), accum[i]);
            historyImages.retain(cmd.address(), historyDepth[i]);
            // Destinations are overwritten completely. The source needs an
            // initial layout only after a reset; valid history is kept intact.
            if (i == write || !historyWritten) {
                VkBarriers.transition(cmd, accum[i].image(), VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
                VkBarriers.transition(cmd, historyDepth[i].image(), VK10.VK_IMAGE_ASPECT_COLOR_BIT,
                    VK10.VK_IMAGE_LAYOUT_UNDEFINED, VK10.VK_IMAGE_LAYOUT_GENERAL);
            }
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer push = stack.malloc(DISPLAY_PUSH_BYTES);
            push.putInt(0, accumFrames).putInt(4, flags).putFloat(8, 1.0f).putFloat(12, MAX_RADIANCE);
            push.putFloat(16, dispatch.diCamDeltaX()).putFloat(20, dispatch.diCamDeltaY())
                .putFloat(24, dispatch.diCamDeltaZ()).putFloat(28, REPROJECTION_TOLERANCE);
            display.dispatch(cmd.address(), display.groups(width, 0), display.groups(height, 1), 1, List.of(
                SlangProgram.Resource.storageImage("radiance", trace.radianceView()),
                SlangProgram.Resource.storageImage("accum", accum[write].view()),
                SlangProgram.Resource.storageImage("outImage", views.output()),
                SlangProgram.Resource.storageImage("depth", views.depth()),
                SlangProgram.Resource.storageImage("historyDepth", historyDepth[write].view()),
                SlangProgram.Resource.storageImage("motion", trace.motionView()),
                SlangProgram.Resource.storageImage("previousAccum", accum[read].view()),
                SlangProgram.Resource.storageImage("previousDepth", historyDepth[read].view()),
                trace.frameResource()), push);
        }
        historyParity ^= 1;
        di.afterDispatch(diFlags != 0);
        VkBarriers.fullMemoryBarrier(cmd); // rt_output and rt_depth are read by the composite, bloom and upscalers next
    }

    @Override public boolean spatialAvailable() { return spatialReuse != null; }

    static int displayFlags(int frameFlags) {
        int flags = DISPLAY_FLAG_TONEMAP;
        if ((frameFlags & BedrayCoreLayouts.PATH_TRACE_FRAME_ACCUMULATE) != 0) flags |= DISPLAY_FLAG_ACCUMULATE;
        if ((frameFlags & BedrayCoreLayouts.PATH_TRACE_FRAME_CAMERA_STATIC) != 0) flags |= DISPLAY_FLAG_CAMERA_STATIC;
        if ((frameFlags & BedrayCoreLayouts.PATH_TRACE_FRAME_FLIP_Y) != 0) flags |= DISPLAY_FLAG_FLIP_Y;
        if ((frameFlags & BedrayCoreLayouts.PATH_TRACE_FRAME_RESET_HISTORY) == 0) flags |= DISPLAY_FLAG_REPROJECT;
        return flags;
    }
    @Override public String reservoirState() {
        return di.mode(true, reservoirs != null, false);
    }
    @Override public void releaseRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) return;
        trace.releaseRecording(commandBuffer);
        historyImages.releaseRecording(commandBuffer);
        nativeBuffers.releaseRecording(commandBuffer);
        if (spatialReuse != null) spatialReuse.releaseRecording(commandBuffer);
        runtime.releaseRecording(commandBuffer);
        recordings.remove(commandBuffer);
    }
    @Override public boolean discardRecording(long commandBuffer) {
        if (!recordings.contains(commandBuffer)) return false;
        di.reset();
        releaseRecording(commandBuffer);
        return true;
    }
    @Override public void close() {
        if (closed) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("reference backend still has pending recordings");
        trace.close();
        if (spatialReuse != null) spatialReuse.close();
        historyImages.close();
        nativeBuffers.close();
        closed = true;
    }

    @Override public void releaseRecordingsAfterHostDrain() {
        for (long command : List.copyOf(recordings)) releaseRecording(command);
    }
}
