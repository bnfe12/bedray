package io.github.andrexarlay.vulkancompat.rtx.upscaler;

import io.github.andrexarlay.vulkancompat.rt.core.BedrayCoreLayouts;
import io.github.andrexarlay.vulkancompat.rt.core.BedrayVelocityNative;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangProgram;
import io.github.andrexarlay.vulkancompat.rt.slang.SlangRuntime;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Host POD adapter for the live RG16F velocity AOT pass. Motion remains previous
 * minus current in render pixels; native owns immutable uploads and barriers.
 * The shared runtime owns programs/samplers and the host supplies exact command
 * completion/discard before resources can be reused or closed.
 */
public final class VelocityPipeline implements AutoCloseable {
    public static final String PROGRAM_NAME = "velocity_reproject_live";
    public static final int UNIFORM_FLOATS = 92;
    public static final long UNIFORM_BYTES = BedrayVelocityNative.UNIFORM_BYTES;
    public static final int DEPTH_HARDWARE = 0, DEPTH_LINEAR_VIEW_Z = 1, DEPTH_RAY_DISTANCE = 2;
    public static final String TELEMETRY_LINE =
        "[RT-MV] source=VELOCITY_REPROJECT convention=PREV_MINUS_CURR units=PIXELS cameraValid=UNKNOWN entityValid=UNKNOWN";

    private final SlangRuntime runtime;
    private final BedrayVelocityNative pass;
    private final Set<Long> recordings = new LinkedHashSet<>();
    private boolean closed;

    public VelocityPipeline(SlangRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "shared SlangRuntime is required");
        var program = runtime.program(PROGRAM_NAME).orElseThrow(() -> new IllegalStateException("velocity AOT program missing"));
        require(program);
        pass = new BedrayVelocityNative(runtime.library(), runtime.core(), program.nativeHandle(), runtime.atlasSampler(), 1L << 20);
    }

    private static void require(SlangProgram program) {
        var manifest = program.manifest();
        if (!java.util.Arrays.equals(manifest.threadGroupSize(), new int[]{8, 8, 1})
            || manifest.pushConstantBytes() != 0 || manifest.bindings().size() != 3)
            throw new IllegalStateException("invalid live velocity manifest");
        String[] names = {"depthTexture", "outMotionVectors", "frame"};
        int[] types = {BedrayCoreLayouts.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
            BedrayCoreLayouts.DESCRIPTOR_TYPE_STORAGE_IMAGE, BedrayCoreLayouts.DESCRIPTOR_TYPE_UNIFORM_BUFFER};
        for (int i = 0; i < names.length; i++) {
            var binding = manifest.binding(names[i]);
            if (binding == null || binding.binding() != i || binding.descriptorType() != types[i]
                || (i == 2 && binding.elementBytes() != UNIFORM_BYTES))
                throw new IllegalStateException("invalid live velocity binding: " + names[i]);
        }
    }

    public static String getTelemetryString(boolean cameraValid, boolean entityValid) {
        return String.format(java.util.Locale.ROOT,
            "[RT-MV] source=VELOCITY_REPROJECT convention=PREV_MINUS_CURR units=PIXELS cameraValid=%s entityValid=%s",
            cameraValid, entityValid);
    }

    public synchronized void init() {
        if (closed) throw new IllegalStateException("velocity pass is closed");
    }

    public void dispatch(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                         Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta) {
        dispatch(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, null, null, 0, false);
    }

    public void dispatch(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                         Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta,
                         Matrix4f entityCurrTransform, Matrix4f entityPrevTransform, int entityCustomIndex, boolean historyReset) {
        record(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta,
            entityCurrTransform, entityPrevTransform, entityCustomIndex, historyReset, DEPTH_HARDWARE, 0, 0, 0);
    }

    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta) {
        dispatchLinearViewZ(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, 0, 0, 0);
    }
    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, long entityMotionAddress) {
        dispatchLinearViewZ(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, entityMotionAddress, 0, 0);
    }
    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, long entityMotionAddress, long maskAddress) {
        dispatchLinearViewZ(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, entityMotionAddress, maskAddress, 0);
    }
    /** Borrowed BDA inputs: float4 entity motion, float output mask, float previous view-Z per pixel. */
    public void dispatchLinearViewZ(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, long entityMotionAddress, long maskAddress,
                                    long prevDepthAddress) {
        record(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, null, null, 0, false,
            DEPTH_LINEAR_VIEW_Z, entityMotionAddress, maskAddress, prevDepthAddress);
    }
    public void dispatchRayDistance(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta) {
        record(cmd, depthView, mvView, width, height, invViewProj, prevViewProj, camDelta, null, null, 0, false,
            DEPTH_RAY_DISTANCE, 0, 0, 0);
    }

    private synchronized void record(VkCommandBuffer cmd, long depthView, long mvView, int width, int height,
                                    Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta,
                                    Matrix4f entityCurrTransform, Matrix4f entityPrevTransform, int entityCustomIndex, boolean historyReset,
                                    int convention, long entityMotion, long mask, long previousDepth) {
        if (closed) throw new IllegalStateException("velocity pass is closed");
        Objects.requireNonNull(cmd, "command buffer is required");
        if (depthView == 0 || mvView == 0 || depthView == mvView || width <= 0 || height <= 0)
            throw new IllegalArgumentException("invalid velocity images/extent");
        recordings.add(cmd.address());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer uniforms = stack.calloc(BedrayVelocityNative.UNIFORM_BYTES);
            Vector3f bobOrigin = io.github.andrexarlay.vulkancompat.rtx.WorldTargetManager.levelCameraOriginOr(null);
            boolean jittered = CameraJitterManager.isActive();
            fillUniforms(uniforms, invViewProj, prevViewProj, camDelta, bobOrigin, width, height,
                entityCurrTransform, entityPrevTransform, entityCustomIndex, historyReset, convention,
                entityMotion, mask, previousDepth,
                jittered ? CameraJitterManager.getCurrentPixelJitterX() : 0f,
                jittered ? CameraJitterManager.getCurrentPixelJitterY() : 0f,
                jittered ? CameraJitterManager.getPrevPixelJitterX() : 0f,
                jittered ? CameraJitterManager.getPrevPixelJitterY() : 0f);
            pass.record(cmd.address(), depthView, mvView, width, height, uniforms);
        }
    }

    static void fillUniforms(ByteBuffer data, Matrix4f invViewProj, Matrix4f prevViewProj, Vec3 camDelta, Vector3f bobOrigin,
                             int width, int height, Matrix4f entityCurrent, Matrix4f entityPrevious, int entityIndex,
                             boolean historyReset, int convention, long entityMotion, long mask, long previousDepth,
                             float jitterX, float jitterY, float previousJitterX, float previousJitterY) {
        if (data.remaining() != UNIFORM_BYTES || width <= 0 || height <= 0 || convention < 0 || convention > 2)
            throw new IllegalArgumentException("invalid velocity POD/extent/convention");
        data = data.slice().order(ByteOrder.nativeOrder());
        (invViewProj != null ? invViewProj : new Matrix4f()).get(0, data);
        (prevViewProj != null ? prevViewProj : new Matrix4f()).get(64, data);
        data.putFloat(128, camDelta != null ? (float) camDelta.x : 0f);
        data.putFloat(132, camDelta != null ? (float) camDelta.y : 0f);
        data.putFloat(136, camDelta != null ? (float) camDelta.z : 0f).putFloat(140, convention);
        data.putFloat(144, bobOrigin.x).putFloat(148, bobOrigin.y).putFloat(152, bobOrigin.z).putFloat(156, 0f);
        data.putFloat(160, width).putFloat(164, height).putFloat(168, 1f / width).putFloat(172, 1f / height);
        Matrix4f current = entityCurrent != null ? entityCurrent : new Matrix4f();
        current.get(176, data); (entityPrevious != null ? entityPrevious : current).get(240, data);
        data.putFloat(304, entityCurrent != null ? 1f : 0f).putFloat(308, entityIndex);
        data.putFloat(312, historyReset ? 1f : 0f).putFloat(316, 16f);
        data.putLong(320, entityMotion).putLong(328, mask).putLong(336, previousDepth).putLong(344, 0);
        data.putFloat(352, jitterX).putFloat(356, jitterY).putFloat(360, previousJitterX).putFloat(364, previousJitterY);
    }

    public record ReprojectionResult(
        float mvX,
        float mvY,
        float dynamicObjectMask,
        float disocclusionMask,
        boolean valid
    ) {}

    /**
     * CPU reference reprojection implementation matching velocity_reproject.comp.
     * Computes motion vectors, dynamicObjectMask, and disocclusionMask while protecting against NaN/Inf and teleports.
     */
    public static ReprojectionResult reprojectCpu(
        float uvX,
        float uvY,
        float depth,
        int width,
        int height,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta,
        boolean isEntity,
        Matrix4f entityCurrTransform,
        Matrix4f entityPrevTransform,
        int entityCustomIndex,
        boolean historyReset
    ) {
        return reprojectCpu(uvX, uvY, depth, width, height, invViewProj, prevViewProj, camDelta, isEntity,
            entityCurrTransform, entityPrevTransform, entityCustomIndex, historyReset, DEPTH_HARDWARE, new org.joml.Vector3f());
    }

    /** CPU reference overload with the depth convention and bobbed camera origin used by the compute shader. */
    public static ReprojectionResult reprojectCpu(
        float uvX,
        float uvY,
        float depth,
        int width,
        int height,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta,
        boolean isEntity,
        Matrix4f entityCurrTransform,
        Matrix4f entityPrevTransform,
        int entityCustomIndex,
        boolean historyReset,
        int depthConvention,
        org.joml.Vector3f cameraOrigin
    ) {
        return reprojectCpu(uvX, uvY, depth, width, height, invViewProj, prevViewProj, camDelta, isEntity,
            entityCurrTransform, entityPrevTransform, entityCustomIndex, historyReset, depthConvention, cameraOrigin,
            0.0f, 0.0f);
    }

    /**
     * CPU reference overload with the sub-pixel jitter of the current projection, in render pixels: the depth of a pixel
     * belongs to the unjittered pixel {@code p + jitter}, exactly as in {@code velocity_reproject.comp}.
     */
    public static ReprojectionResult reprojectCpu(
        float uvX,
        float uvY,
        float depth,
        int width,
        int height,
        Matrix4f invViewProj,
        Matrix4f prevViewProj,
        Vec3 camDelta,
        boolean isEntity,
        Matrix4f entityCurrTransform,
        Matrix4f entityPrevTransform,
        int entityCustomIndex,
        boolean historyReset,
        int depthConvention,
        org.joml.Vector3f cameraOrigin,
        float jitterX,
        float jitterY
    ) {
        // The jittered projection moved the content by -jitter pixels; this pixel holds the unjittered p + jitter.
        float sampleUvX = uvX + (width > 0 ? jitterX / width : 0.0f);
        float sampleUvY = uvY + (height > 0 ? jitterY / height : 0.0f);
        float ndcX = sampleUvX * 2.0f - 1.0f;
        float ndcY = sampleUvY * 2.0f - 1.0f;

        Matrix4f invVP = invViewProj != null ? invViewProj : new Matrix4f();
        Matrix4f prevVP = prevViewProj != null ? prevViewProj : new Matrix4f();

        org.joml.Vector4f prevClip;
        float dynamicObjectMask = 0.0f;
        float disocclusionMask = 0.0f;

        boolean skyDepth = depthConvention == DEPTH_LINEAR_VIEW_Z || depthConvention == DEPTH_RAY_DISTANCE
            ? Math.abs(depth) >= 1.0e5f : depth >= 1.0e5f || depth <= 1.0e-7f;
        org.joml.Vector3f origin = cameraOrigin != null ? new org.joml.Vector3f(cameraOrigin) : new org.joml.Vector3f();
        if (skyDepth) {
            org.joml.Vector4f rayW = invVP.transform(new org.joml.Vector4f(ndcX, ndcY, 0.5f, 1.0f));
            org.joml.Vector3f rayDir = new org.joml.Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w).sub(origin).normalize();
            prevClip = prevVP.transform(new org.joml.Vector4f(rayDir, 0.0f));
        } else {
            org.joml.Vector4f rayW = invVP.transform(new org.joml.Vector4f(ndcX, ndcY, 0.5f, 1.0f));
            org.joml.Vector3f unprojected = new org.joml.Vector3f(rayW.x / rayW.w, rayW.y / rayW.w, rayW.z / rayW.w);
            org.joml.Vector3f worldPosRel;
            org.joml.Vector4f worldPosRel4;
            if (depthConvention == DEPTH_LINEAR_VIEW_Z) {
                org.joml.Vector3f dir = new org.joml.Vector3f(unprojected).sub(origin).normalize();
                org.joml.Vector4f fwdW = invVP.transform(new org.joml.Vector4f(0.0f, 0.0f, 0.5f, 1.0f));
                org.joml.Vector3f forward = new org.joml.Vector3f(fwdW.x / fwdW.w, fwdW.y / fwdW.w, fwdW.z / fwdW.w).sub(origin).normalize();
                worldPosRel = new org.joml.Vector3f(origin).fma(Math.abs(depth) / Math.max(dir.dot(forward), 1.0e-4f), dir);
                worldPosRel4 = new org.joml.Vector4f(worldPosRel, 1.0f);
            } else if (depthConvention == DEPTH_RAY_DISTANCE) {
                org.joml.Vector3f dir = new org.joml.Vector3f(unprojected).sub(origin).normalize();
                worldPosRel = new org.joml.Vector3f(origin).fma(depth, dir);
                worldPosRel4 = new org.joml.Vector4f(worldPosRel, 1.0f);
            } else {
                worldPosRel4 = invVP.transform(new org.joml.Vector4f(ndcX, ndcY, depth, 1.0f));
                worldPosRel = new org.joml.Vector3f(worldPosRel4.x / worldPosRel4.w, worldPosRel4.y / worldPosRel4.w, worldPosRel4.z / worldPosRel4.w);
            }

            if (isEntity && entityCurrTransform != null) {
                org.joml.Vector3f currPos = entityCurrTransform.getTranslation(new org.joml.Vector3f());
                org.joml.Vector3f prevPos = entityPrevTransform != null
                    ? entityPrevTransform.getTranslation(new org.joml.Vector3f())
                    : currPos;
                float moveDist = currPos.distance(prevPos);
                boolean teleported = historyReset || moveDist > 16.0f;

                dynamicObjectMask = moveDist > 1.0e-4f ? 1.0f : 0.0f;
                disocclusionMask = 0.0f;

                if (teleported) {
                    disocclusionMask = 1.0f;
                    prevClip = new org.joml.Vector4f(ndcX * worldPosRel4.w, ndcY * worldPosRel4.w, 0.5f, 1.0f);
                } else {
                    Matrix4f invCurr = new Matrix4f(entityCurrTransform).invert();
                    org.joml.Vector4f localPos = invCurr.transform(new org.joml.Vector4f(worldPosRel, 1.0f));
                    Matrix4f prevEnt = entityPrevTransform != null ? entityPrevTransform : entityCurrTransform;
                    org.joml.Vector4f prevPosRel = prevEnt.transform(localPos);
                    prevClip = prevVP.transform(new org.joml.Vector4f(prevPosRel.x, prevPosRel.y, prevPosRel.z, 1.0f));
                }
            } else {
                float cdx = camDelta != null ? (float) camDelta.x : 0.0f;
                float cdy = camDelta != null ? (float) camDelta.y : 0.0f;
                float cdz = camDelta != null ? (float) camDelta.z : 0.0f;
                org.joml.Vector4f prevPosRel = new org.joml.Vector4f(worldPosRel.x + cdx, worldPosRel.y + cdy, worldPosRel.z + cdz, 1.0f);
                prevClip = prevVP.transform(prevPosRel);
            }
        }

        float prevUvX = sampleUvX;
        float prevUvY = sampleUvY;
        if (Math.abs(prevClip.w) > 1e-7f) {
            prevUvX = (prevClip.x / prevClip.w) * 0.5f + 0.5f;
            prevUvY = (prevClip.y / prevClip.w) * 0.5f + 0.5f;
        } else {
            disocclusionMask = 1.0f;
        }

        if (prevUvX < 0.0f || prevUvX > 1.0f || prevUvY < 0.0f || prevUvY > 1.0f) {
            disocclusionMask = 1.0f;
        }

        float mvX = (prevUvX - sampleUvX) * width;
        float mvY = (prevUvY - sampleUvY) * height;

        float maxMotion = Math.max(width, height) * 0.5f;
        float len = (float) Math.hypot(mvX, mvY);
        if (len > maxMotion) {
            mvX = (mvX / len) * maxMotion;
            mvY = (mvY / len) * maxMotion;
        }

        if (Float.isNaN(mvX) || Float.isNaN(mvY) || Float.isInfinite(mvX) || Float.isInfinite(mvY)) {
            mvX = 0.0f;
            mvY = 0.0f;
            disocclusionMask = 1.0f;
        }
        if (Float.isNaN(dynamicObjectMask) || Float.isInfinite(dynamicObjectMask)) {
            dynamicObjectMask = 0.0f;
        }
        if (Float.isNaN(disocclusionMask) || Float.isInfinite(disocclusionMask)) {
            disocclusionMask = 1.0f;
        }

        return new ReprojectionResult(mvX, mvY, dynamicObjectMask, disocclusionMask, true);
    }

    public synchronized void releaseRecording(long command) {
        if (!recordings.contains(command)) return;
        runtime.releaseRecording(command); recordings.remove(command);
    }
    public synchronized void discardRecording(long command) {
        if (!recordings.contains(command)) return;
        runtime.discardRecording(command); recordings.remove(command);
    }
    public synchronized boolean hasPendingRecordings() { return !recordings.isEmpty(); }
    /** The owning host proved that its pending encoder work was submitted and completed. */
    public synchronized void releaseRecordingsAfterHostDrain() {
        for (long command : java.util.List.copyOf(recordings)) releaseRecording(command);
    }
    @Override public synchronized void close() { cleanup(); }
    public synchronized void cleanup() {
        if (closed) return;
        if (!recordings.isEmpty()) throw new IllegalStateException("velocity recordings require completion/discard");
        pass.close(); closed = true;
    }
    public synchronized boolean isInitialized() { return !closed; }
}
