package io.github.andrexarlay.vulkancompat.rt.upscale.dlss.nr;

import java.util.Objects;

/**
 * Configuration parameters for the experimental DLSS 5 Neural Rendering (DLSS-NR) pass.
 * <p>
 * Parameter semantics match OptiScaler's {@code [DlssNr]} implementation (based on RenoDX's DLSS 5 addon):
 * <ul>
 *   <li>{@code enabled}: Off by default. Strictly experimental, opt-in feature.</li>
 *   <li>{@code transferStrength}: How far the frame moves toward the model's picture [0.0 .. 2.0].
 *       0.0 = true bypass (exact upscaler output), 1.0 = model picture, &gt; 1.0 carries past it.</li>
 *   <li>{@code colourStrength}: How much model color arrives with its light [0.0 .. 1.0].
 *       0.0 = keeps game hue strictly (luminance-only transfer), 1.0 = model color arrives too.</li>
 *   <li>{@code maxRatio}: Highlight guard [1.0 .. 10.0, default 2.0]. Maximum luminance brightening ratio.</li>
 *   <li>{@code workingScale}: Fraction of the frame the model works at [0.5 .. 1.0, default 1.0].</li>
 *   <li>{@code debugView}: 0=Off, 1=Model Input, 2=Raw Model, 3=Difference (20x amplified).</li>
 * </ul>
 */
public final class DlssNrConfig {

    public static final boolean DEFAULT_ENABLED = false;
    public static final float DEFAULT_TRANSFER_STRENGTH = 1.0f;
    public static final float DEFAULT_COLOUR_STRENGTH = 1.0f;
    public static final float DEFAULT_MAX_RATIO = 2.0f;
    public static final float DEFAULT_WORKING_SCALE = 1.0f;
    public static final int DEFAULT_DEBUG_VIEW = 0;

    public static final float MIN_TRANSFER_STRENGTH = 0.0f;
    public static final float MAX_TRANSFER_STRENGTH = 2.0f;
    public static final float MIN_COLOUR_STRENGTH = 0.0f;
    public static final float MAX_COLOUR_STRENGTH = 1.0f;
    public static final float MIN_MAX_RATIO = 1.0f;
    public static final float MAX_MAX_RATIO = 10.0f;
    public static final float MIN_WORKING_SCALE = 0.5f;
    public static final float MAX_WORKING_SCALE = 1.0f;

    public static final int DEBUG_VIEW_OFF = 0;
    public static final int DEBUG_VIEW_INPUT = 1;
    public static final int DEBUG_VIEW_RAW = 2;
    public static final int DEBUG_VIEW_DIFFERENCE = 3;

    // Optional / extended OptiScaler model controls
    public static final int DEFAULT_PRESET = 0;
    public static final int DEFAULT_STYLE = 0;
    public static final float DEFAULT_INTENSITY = 1.0f;
    public static final float DEFAULT_LOCAL_STRUCTURE = 1.0f;
    public static final float DEFAULT_LOCAL_TONE = 1.0f;
    public static final float DEFAULT_SKIN_STRUCTURE = -1.0f;
    public static final boolean DEFAULT_AUTO_MASK = true;
    public static final boolean DEFAULT_AUTO_WHITE_POINT = true;
    public static final float DEFAULT_WHITE_POINT_SCALE = 1.0f;

    private boolean enabled = DEFAULT_ENABLED;
    private float transferStrength = DEFAULT_TRANSFER_STRENGTH;
    private float colourStrength = DEFAULT_COLOUR_STRENGTH;
    private float maxRatio = DEFAULT_MAX_RATIO;
    private float workingScale = DEFAULT_WORKING_SCALE;
    private int debugView = DEFAULT_DEBUG_VIEW;

    private int preset = DEFAULT_PRESET;
    private int style = DEFAULT_STYLE;
    private float intensity = DEFAULT_INTENSITY;
    private float localStructure = DEFAULT_LOCAL_STRUCTURE;
    private float localTone = DEFAULT_LOCAL_TONE;
    private float skinStructure = DEFAULT_SKIN_STRUCTURE;
    private boolean autoMask = DEFAULT_AUTO_MASK;
    private boolean autoWhitePoint = DEFAULT_AUTO_WHITE_POINT;
    private float whitePointScale = DEFAULT_WHITE_POINT_SCALE;

    public DlssNrConfig() {
    }

    public static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) return min;
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public void resetToDefaults() {
        this.enabled = DEFAULT_ENABLED;
        this.transferStrength = DEFAULT_TRANSFER_STRENGTH;
        this.colourStrength = DEFAULT_COLOUR_STRENGTH;
        this.maxRatio = DEFAULT_MAX_RATIO;
        this.workingScale = DEFAULT_WORKING_SCALE;
        this.debugView = DEFAULT_DEBUG_VIEW;
        this.preset = DEFAULT_PRESET;
        this.style = DEFAULT_STYLE;
        this.intensity = DEFAULT_INTENSITY;
        this.localStructure = DEFAULT_LOCAL_STRUCTURE;
        this.localTone = DEFAULT_LOCAL_TONE;
        this.skinStructure = DEFAULT_SKIN_STRUCTURE;
        this.autoMask = DEFAULT_AUTO_MASK;
        this.autoWhitePoint = DEFAULT_AUTO_WHITE_POINT;
        this.whitePointScale = DEFAULT_WHITE_POINT_SCALE;
    }

    public DlssNrConfig copy() {
        DlssNrConfig copy = new DlssNrConfig();
        copy.enabled = this.enabled;
        copy.transferStrength = this.transferStrength;
        copy.colourStrength = this.colourStrength;
        copy.maxRatio = this.maxRatio;
        copy.workingScale = this.workingScale;
        copy.debugView = this.debugView;
        copy.preset = this.preset;
        copy.style = this.style;
        copy.intensity = this.intensity;
        copy.localStructure = this.localStructure;
        copy.localTone = this.localTone;
        copy.skinStructure = this.skinStructure;
        copy.autoMask = this.autoMask;
        copy.autoWhitePoint = this.autoWhitePoint;
        copy.whitePointScale = this.whitePointScale;
        return copy;
    }

    /**
     * Checks if this pass should be bypassed (either disabled or transferStrength is zero).
     */
    public boolean isBypass() {
        return !enabled || (transferStrength <= 0.0f && debugView == DEBUG_VIEW_OFF);
    }

    // Getters and Setters with automatic clamping

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public float getTransferStrength() {
        return transferStrength;
    }

    public void setTransferStrength(float transferStrength) {
        this.transferStrength = clamp(transferStrength, MIN_TRANSFER_STRENGTH, MAX_TRANSFER_STRENGTH);
    }

    public float getColourStrength() {
        return colourStrength;
    }

    public void setColourStrength(float colourStrength) {
        this.colourStrength = clamp(colourStrength, MIN_COLOUR_STRENGTH, MAX_COLOUR_STRENGTH);
    }

    public float getMaxRatio() {
        return maxRatio;
    }

    public void setMaxRatio(float maxRatio) {
        this.maxRatio = clamp(maxRatio, MIN_MAX_RATIO, MAX_MAX_RATIO);
    }

    public float getWorkingScale() {
        return workingScale;
    }

    public void setWorkingScale(float workingScale) {
        this.workingScale = clamp(workingScale, MIN_WORKING_SCALE, MAX_WORKING_SCALE);
    }

    public int getDebugView() {
        return debugView;
    }

    public void setDebugView(int debugView) {
        this.debugView = clamp(debugView, DEBUG_VIEW_OFF, DEBUG_VIEW_DIFFERENCE);
    }

    public int getPreset() {
        return preset;
    }

    public void setPreset(int preset) {
        this.preset = preset;
    }

    public int getStyle() {
        return style;
    }

    public void setStyle(int style) {
        this.style = style;
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = Math.max(0.0f, intensity);
    }

    public float getLocalStructure() {
        return localStructure;
    }

    public void setLocalStructure(float localStructure) {
        this.localStructure = localStructure;
    }

    public float getLocalTone() {
        return localTone;
    }

    public void setLocalTone(float localTone) {
        this.localTone = localTone;
    }

    public float getSkinStructure() {
        return skinStructure;
    }

    public void setSkinStructure(float skinStructure) {
        this.skinStructure = skinStructure;
    }

    public boolean isAutoMask() {
        return autoMask;
    }

    public void setAutoMask(boolean autoMask) {
        this.autoMask = autoMask;
    }

    public boolean isAutoWhitePoint() {
        return autoWhitePoint;
    }

    public void setAutoWhitePoint(boolean autoWhitePoint) {
        this.autoWhitePoint = autoWhitePoint;
    }

    public float getWhitePointScale() {
        return whitePointScale;
    }

    public void setWhitePointScale(float whitePointScale) {
        this.whitePointScale = Math.max(0.0f, whitePointScale);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DlssNrConfig that = (DlssNrConfig) o;
        return enabled == that.enabled
            && Float.compare(that.transferStrength, transferStrength) == 0
            && Float.compare(that.colourStrength, colourStrength) == 0
            && Float.compare(that.maxRatio, maxRatio) == 0
            && Float.compare(that.workingScale, workingScale) == 0
            && debugView == that.debugView
            && preset == that.preset
            && style == that.style
            && Float.compare(that.intensity, intensity) == 0
            && Float.compare(that.localStructure, localStructure) == 0
            && Float.compare(that.localTone, localTone) == 0
            && Float.compare(that.skinStructure, skinStructure) == 0
            && autoMask == that.autoMask
            && autoWhitePoint == that.autoWhitePoint
            && Float.compare(that.whitePointScale, whitePointScale) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, transferStrength, colourStrength, maxRatio, workingScale,
            debugView, preset, style, intensity, localStructure, localTone, skinStructure,
            autoMask, autoWhitePoint, whitePointScale);
    }

    @Override
    public String toString() {
        return "DlssNrConfig{" +
            "enabled=" + enabled +
            ", transferStrength=" + transferStrength +
            ", colourStrength=" + colourStrength +
            ", maxRatio=" + maxRatio +
            ", workingScale=" + workingScale +
            ", debugView=" + debugView +
            ", preset=" + preset +
            ", style=" + style +
            ", intensity=" + intensity +
            '}';
    }
}
