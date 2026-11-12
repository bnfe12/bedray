package io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline;

import io.github.andrexarlay.vulkanbridge.platform.LogPaths;
import io.github.andrexarlay.vulkancompat.rt.entity.RtSceneInstances;

import static io.github.andrexarlay.vulkancompat.shaderhost.rtpipeline.RtPipelineLayouts.TOTAL_PASSES;

/** Shared ray-tracing-pipeline pack host state and telemetry; only registered native handles are reported as pipelines. */
public final class RtPipelineStatus {

    private RtPipelineStatus() {}

    public enum State {
        UNAVAILABLE,
        DISCOVERED,
        PARSING,
        COMPILING,
        CREATING_MODULES,
        CREATING_PIPELINE,
        CREATING_SBT,
        VALIDATING,
        READY,
        ACTIVATING,
        ACTIVE,
        FAILED,
        ROLLING_BACK
    }

    public record TlasBinding(
            int set,
            int binding,
            String name,
            long tlasAddressOrHandle,
            boolean includesEntities,
            int instanceMask
    ) {
        public static final String ENTITY_TLAS_NAME = "tlas";
        public static final int ENTITY_TLAS_SET = 0;
        public static final int ENTITY_TLAS_BINDING = 0;

        public String format() {
            return tlasAddressOrHandle != 0L ? ("0x" + Long.toHexString(tlasAddressOrHandle)) : "N/A";
        }
    }

    private static volatile State currentState = State.UNAVAILABLE;
    private static volatile String selectedPack = "none";
    private static volatile long currentPipelineHandle = 0L;
    private static volatile int pipelineGeneration = 0;
    private static volatile long currentShaderHash = 0L;
    private static volatile long currentPipelineHash = 0L;
    private static volatile int currentShaderModules = 0;
    private static volatile long currentSbtAddress = 0L;
    private static volatile int currentSbtGroups = 0;
    private static volatile int currentDescriptors = 0;
    private static volatile long currentTraceCalls = 0L;
    private static volatile long currentFramesRendered = 0L;
    private static volatile String lastError = "none";
    private static volatile long rollbackPipelineHandle = 0L;
    // No invented handle: a fallback pipeline exists only when a caller registers a real one.
    private static volatile long nativeRtFallbackPipeline = 0L;
    private static volatile String entityLayoutStatus = "COMPATIBLE";
    private static volatile TlasBinding currentTlasBinding = null;
    private static volatile long currentTlasDescriptor = 0L;

    public static synchronized void updateState(State state, String pack, String error) {
        currentState = state;
        if (pack != null) selectedPack = pack;
        if (error != null) lastError = error;
    }

    public static synchronized void onPipelineCreated(long handle, int modules, int groups, int descriptors) {
        currentPipelineHandle = handle;
        currentShaderModules = modules;
        currentSbtGroups = groups;
        currentDescriptors = descriptors;
        pipelineGeneration++;
        currentState = State.READY;
    }

    public static synchronized void onPipelineCreated(long handle, long tlasHandle, int modules, int groups, int descriptors) {
        currentPipelineHandle = handle;
        currentTlasDescriptor = tlasHandle;
        currentShaderModules = modules;
        currentSbtGroups = groups;
        currentDescriptors = descriptors;
        pipelineGeneration++;
        currentState = State.READY;
    }

    public static synchronized void onFrameRendered() {
        currentFramesRendered++;
        currentTraceCalls += TOTAL_PASSES;
        if (currentPipelineHandle != 0L && (currentState == State.READY || currentState == State.ACTIVATING)) {
            currentState = State.ACTIVE;
        }
    }

    public static synchronized void onRollback(long fallbackHandle, String reason) {
        rollbackPipelineHandle = currentPipelineHandle;
        currentPipelineHandle = fallbackHandle;
        currentState = State.ROLLING_BACK;
        lastError = reason;
    }

    public static synchronized void failEntityCompatibility(String reason) {
        currentState = State.FAILED;
        entityLayoutStatus = "INCOMPATIBLE";
        lastError = (reason != null && !reason.isBlank()) ? reason : "Entity TLAS layout unsupported by pack";
        if (nativeRtFallbackPipeline != 0L && currentPipelineHandle == 0L) {
            currentPipelineHandle = nativeRtFallbackPipeline;
        }
    }

    public static synchronized void onFailure(String error, long fallbackPipeline) {
        currentState = State.FAILED;
        lastError = (error != null && !error.isBlank()) ? error : "Unknown failure";
        if (fallbackPipeline != 0L) {
            currentPipelineHandle = fallbackPipeline;
        } else if (nativeRtFallbackPipeline != 0L) {
            currentPipelineHandle = nativeRtFallbackPipeline;
        }
    }

    public static synchronized boolean acceptSceneTlas(long tlasHandle, boolean includesEntities) {
        if (tlasHandle == 0L) {
            currentTlasBinding = null;
            currentTlasDescriptor = 0L;
            return false;
        }
        int mask = includesEntities ? RtSceneInstances.MASK_FULL_SCENE : RtSceneInstances.MASK_WORLD;
        currentTlasBinding = new TlasBinding(0, 0, "tlas", tlasHandle, includesEntities, mask);
        currentTlasDescriptor = tlasHandle;
        entityLayoutStatus = "COMPATIBLE";
        return true;
    }

    public static synchronized boolean acceptSceneTlas(RtSceneInstances sceneInstances) {
        if (sceneInstances == null) {
            failEntityCompatibility("Scene instances contract was null");
            return false;
        }
        long ref = sceneInstances.worldAsReference();
        if (ref == 0L) {
            failEntityCompatibility("Scene TLAS reference was 0");
            return false;
        }
        boolean hasEnt = sceneInstances.hasEntities();
        return acceptSceneTlas(ref, hasEnt);
    }

    public static synchronized void resetState() {
        currentState = State.UNAVAILABLE;
        selectedPack = "none";
        currentPipelineHandle = 0L;
        pipelineGeneration = 0;
        currentShaderHash = 0L;
        currentPipelineHash = 0L;
        currentShaderModules = 0;
        currentSbtAddress = 0L;
        currentSbtGroups = 0;
        currentDescriptors = 0;
        currentTraceCalls = 0L;
        currentFramesRendered = 0L;
        lastError = "none";
        rollbackPipelineHandle = 0L;
        entityLayoutStatus = "COMPATIBLE";
        currentTlasBinding = null;
        currentTlasDescriptor = 0L;
    }

    /** Status line; the detail names what the host could not do, never a placeholder handle. */
    public static String getF3Telemetry() {
        State reportingState = (currentPipelineHandle != 0L && currentFramesRendered > 0L && currentState != State.FAILED && currentState != State.ROLLING_BACK)
            ? State.ACTIVE
            : currentState;
        return String.format(
            "[RT-PIPELINE] selectedPack=%s state=%s pipeline=%s entityLayout=%s traceCalls=%d framesRendered=%d detail=%s",
            selectedPack,
            reportingState,
            currentPipelineHandle != 0L ? ("0x" + Long.toHexString(currentPipelineHandle)) : "N/A",
            entityLayoutStatus,
            currentTraceCalls,
            currentFramesRendered,
            LogPaths.show(lastError)
        );
    }

    public static String getTlasDescriptorString() {
        return currentTlasDescriptor != 0L ? ("0x" + Long.toHexString(currentTlasDescriptor)) : "N/A";
    }

    public static String getDescriptorsTelemetry() {
        return currentDescriptors > 0 ? String.valueOf(currentDescriptors) : "N/A";
    }

    public static String formatDescriptor(long handle) {
        return handle != 0L ? ("0x" + Long.toHexString(handle)) : "N/A";
    }

    public static State getState() { return currentState; }
    public static String getSelectedPack() { return selectedPack; }
    public static long getCurrentPipelineHandle() { return currentPipelineHandle; }
    public static String getEntityLayoutStatus() { return entityLayoutStatus; }
    public static void setEntityLayoutStatus(String status) { entityLayoutStatus = status; }
    public static long getFramesRendered() { return currentFramesRendered; }
    public static long getTraceCalls() { return currentTraceCalls; }
    public static String getLastError() { return LogPaths.show(lastError); }
    public static TlasBinding getCurrentTlasBinding() { return currentTlasBinding; }
    public static void setNativeRtFallbackPipeline(long handle) { nativeRtFallbackPipeline = handle; }
    public static long getNativeRtFallbackPipeline() { return nativeRtFallbackPipeline; }
}
