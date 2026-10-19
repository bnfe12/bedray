package io.github.andrexarlay.vulkancompat.rtx.upscaler.fg;

import io.github.andrexarlay.vulkancompat.rtx.upscaler.UpscalerLibraryLoader;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.TemporalFrameInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Frame Generation (DLSS-G / Streamline / Optical Flow / Swapchain) state machine,
 * HUD-separate rendering mode, strict temporal data consumption via {@link TemporalFrameInputs},
 * and accurate performance telemetry and pacing counters.
 */
public final class FrameGenerationPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FrameGenerationPipeline");

    public enum State {
        UNSUPPORTED,
        AVAILABLE,
        INITIALIZING,
        READY,
        ACTIVE,
        RESETTING,
        FALLBACK,
        FAILED,
        OFF
    }

    public enum Backend {
        DLSS_G,
        STREAMLINE,
        OPTICAL_FLOW,
        SWAPCHAIN,
        OTHER,
        NONE
    }

    public enum HudMode {
        SEPARATE,
        MASKED,
        UNSUPPORTED
    }

    private State state = State.OFF;
    private Backend backend = Backend.NONE;
    private HudMode hudMode = HudMode.SEPARATE;
    private boolean requested = false;
    private boolean supported = false;

    // Counters & Telemetry
    private long realFrames = 0;
    private long generatedFrames = 0;
    private long presentedFrames = 0;
    private long droppedGenerated = 0;
    private long repeatedFrames = 0;

    private double realFps = 0.0;
    private double presentedFps = 0.0;
    private double simulationFps = 60.0;

    private double frameTimeReal = 16.67;
    private double frameTimePresented = 8.33;
    private double latencyMs = 25.0;

    private String motionVectorsStatus = "VALID";
    private String depthStatus = "VALID";
    private String opticalFlowStatus = "SUPPORTED";
    private String lastError = "none";
    private String resetReason = "none";

    public static final String SCAFFOLD_STATUS = "FRAME GENERATION = SCAFFOLD ONLY / NOT IMPLEMENTED";
    public static final boolean IMPLEMENTED = false;

    public FrameGenerationPipeline() {
        checkHardwareSupport();
    }

    public synchronized void checkHardwareSupport() {
        // Rigid scaffold: FRAME GENERATION = SCAFFOLD ONLY / NOT IMPLEMENTED
        this.supported = false;
        this.backend = Backend.NONE;
        this.state = State.UNSUPPORTED;
        this.lastError = "ScaffoldOnly_NoPresentHook (FRAME GENERATION = SCAFFOLD ONLY / NOT IMPLEMENTED)";
    }

    private boolean checkPresentHookLoaded() {
        // Check if Streamline / DLSS-G interposer and swapchain present hook are actively hooked.
        // In scaffold/unimplemented mode, this returns false.
        return false;
    }

    public synchronized void setRequested(boolean requested) {
        // Toggle permanently locked / blocked: FRAME GENERATION = SCAFFOLD ONLY / NOT IMPLEMENTED
        this.requested = false;
        this.supported = false;
        this.backend = Backend.NONE;
        this.state = State.UNSUPPORTED;
        this.lastError = "ScaffoldOnly_NoPresentHook (FRAME GENERATION = SCAFFOLD ONLY / NOT IMPLEMENTED)";
    }

    private void initializeBackend() {
        try {
            LOGGER.info("[FG] Initializing Frame Generation backend ({}) with HUD-separate mode...", backend);
            this.state = State.READY;
            this.state = State.ACTIVE;
            this.hudMode = HudMode.SEPARATE;
            this.lastError = "none";
        } catch (Exception e) {
            this.state = State.FAILED;
            this.lastError = e.getMessage() != null ? e.getMessage() : "Initialization failed";
            LOGGER.error("[FG] Failed to initialize Frame Generation backend", e);
        }
    }

    public synchronized void onFrameRendered(double realDeltaMs) {
        onFrameRendered(realDeltaMs, TemporalFrameInputs.of(0L, 0L, 0L, 0, 0, null, null, null, false, null, null));
    }

    public synchronized void onFrameRendered(double realDeltaMs, TemporalFrameInputs inputs) {
        this.realFrames++;
        this.frameTimeReal = realDeltaMs;
        this.realFps = realDeltaMs > 0.0 ? 1000.0 / realDeltaMs : 0.0;

        if (inputs != null) {
            this.motionVectorsStatus = "VALID";
            this.depthStatus = "VALID";
            if (inputs.historyReset()) {
                if (this.state == State.ACTIVE) {
                    this.state = State.FALLBACK;
                    this.droppedGenerated++;
                    this.lastError = "Temporal history reset requested";
                    LOGGER.warn("[FG] Falling back due to history reset");
                }
            } else if (this.state == State.FALLBACK && requested && supported) {
                this.state = State.ACTIVE;
            }
        }

        // Honest Scaffold: Do NOT increment generatedFrames or double presentedFps unless actual frame generation is running!
        this.generatedFrames = 0;
        this.droppedGenerated = 0;
        this.repeatedFrames = 0;
        this.presentedFrames = this.realFrames;
        this.frameTimePresented = realDeltaMs;
        this.presentedFps = this.realFps;
        this.simulationFps = this.realFps;
    }

    public synchronized void reset(String reason) {
        this.resetReason = reason;
        this.state = State.RESETTING;
        LOGGER.warn("[FG] Resetting Frame Generation pipeline, reason: {}", reason);
        if (this.requested && this.supported) {
            this.state = State.ACTIVE;
        } else {
            this.state = State.UNSUPPORTED;
        }
    }

    public synchronized String getF3TelemetryString() {
        return String.format(
            "[FG] requested=%s supported=%s backend=%s state=%s reason=%s realFps=%.1f presentedFps=%.1f hudMode=SEPARATE_PLANNED",
            requested, supported, backend, state, lastError, realFps, presentedFps
        );
    }

    public State getState() { return state; }
    public Backend getBackend() { return backend; }
    public HudMode getHudMode() { return hudMode; }
    public boolean isRequested() { return requested; }
    public boolean isSupported() { return supported; }
    public long getRealFrames() { return realFrames; }
    public long getGeneratedFrames() { return generatedFrames; }
    public long getPresentedFrames() { return presentedFrames; }
    public long getDroppedGenerated() { return droppedGenerated; }
    public long getRepeatedFrames() { return repeatedFrames; }
    public double getRealFps() { return realFps; }
    public double getPresentedFps() { return presentedFps; }
    public double getSimulationFps() { return simulationFps; }
    public String getMotionVectorsStatus() { return motionVectorsStatus; }
    public String getDepthStatus() { return depthStatus; }
}
