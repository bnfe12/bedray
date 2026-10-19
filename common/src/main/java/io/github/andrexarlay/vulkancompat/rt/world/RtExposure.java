package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * Auto exposure of the path tracer (pure Java, unit-tested). The shader adds {@code (log2(L) + 16) * 64} of every 16th pixel
 * (L = linear luminance before tone mapping) to the frame slot; two frames later the CPU reads the sum, computes the
 * log-average luminance and moves the exposure towards {@code KEY / average} by {@link #ADAPT} per frame in log2 space,
 * clamped to [{@link #MIN}, {@link #MAX}]. {@link #MIN} is the fixed exposure of the ray mode (RtLighting.EXPOSURE), so the
 * path tracer is never darker than the ray mode.
 */
public final class RtExposure {
    public static final float KEY = 0.22f;
    public static final float MIN = 1.1f;
    /**
     * Brightest auto exposure (4 = +2 EV over the fixed one). It was 16: a torch-lit room or a cave has a log-average luminance
     * far below KEY, so the old range lifted every dim scene by up to +4 EV, washing orange / red light over the walls and
     * erasing the dark of caves (the "everything is too bright / flat" reports). A cave must stay dark; a dim room only needs a nudge.
     */
    public static final float MAX = 4f;
    /** Partial adaptation: the exposure closes only this fraction of the gap (in log space) to KEY / average, like the eye. */
    public static final float STRENGTH = 0.6f;
    /** The measured log-average luminance is smoothed with this time constant (seconds) before it drives the exposure. */
    public static final float TAU_METER = 0.45f;
    /** Slew limit of the exposure in EV per second (brighter / darker): no pumping when a bright sky enters the view. */
    public static final float SLEW_UP_EV = 0.5f;
    public static final float SLEW_DOWN_EV = 1.0f;
    /** Legacy per-frame step of {@link #observe(long, int)} at 60 fps: now only the time constants below matter. */
    public static final float ADAPT = 0.08f;
    /** Adaptation time constants in seconds (exponential, log2 space): slow towards a brighter exposure (dark scene), faster the other way. */
    public static final float TAU_BRIGHTEN = 2.0f;
    public static final float TAU_DARKEN = 0.7f;
    /** Differences below this many EV are ignored (no creeping pulse from the noise of the luminance sum). */
    public static final float DEADBAND_EV = 0.15f;

    private float value = MIN;
    /** Upper clamp of this frame (RtSkyState.maxExposure: lower at night, so the night is not brightened to daylight). */
    private float max = MAX;
    private double targetAnchor = Double.NaN;
    private double meterLog2 = Double.NaN;

    public void setMax(float m) {
        if (Float.isNaN(m) || Float.isInfinite(m)) {
            max = MAX;
            return;
        }
        max = Math.max(MIN, Math.min(MAX, m));
    }

    public float value() {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < MIN) {
            return MIN;
        }
        return value;
    }

    /** One readback at an assumed 60 fps (tests); the renderer passes the real frame time to {@link #observe(long, int, float)}. */
    public void observe(long lumLogSum, int samples) {
        observe(lumLogSum, samples, 1.0f / 60.0f);
    }

    /**
     * One readback: {@code lumLogSum} as written by rt_path.comp over {@code samples} pixels, {@code dt} seconds since the last
     * readback. The exposure follows KEY / average luminance with a time constant (not a per-frame step, so the speed does not
     * depend on the frame rate) and a dead band, slower when the scene gets darker than when it gets brighter.
     */
    public void observe(long lumLogSum, int samples, float dt) {
        if (samples <= 0 || lumLogSum == 0L) {
            return; // the ray pass does not write the sum
        }
        if (Float.isNaN(value) || Float.isInfinite(value) || value < MIN) {
            value = MIN;
            targetAnchor = Double.NaN;
        }
        double avgLog2 = lumLogSum / 64.0 / samples - 16.0;
        if (Double.isNaN(avgLog2) || Double.isInfinite(avgLog2)) {
            return;
        }
        float meterDt = Float.isNaN(dt) || Float.isInfinite(dt) ? 1.0f / 60.0f : Math.max(0.0f, Math.min(dt, 0.25f));
        if (Double.isNaN(meterLog2) || Math.abs(avgLog2 - meterLog2) > 4.0) {
            meterLog2 = avgLog2;   // first frame or a scene cut (teleport, dimension): no smoothing
        } else {
            meterLog2 += (avgLog2 - meterLog2) * (1.0 - Math.exp(-meterDt / TAU_METER));
        }
        avgLog2 = meterLog2;
        double denom = Math.pow(2.0, avgLog2);
        if (Double.isNaN(denom) || denom <= 1.0e-7) {
            return;
        }
        double target = Math.pow(KEY / denom, STRENGTH);
        if (Double.isNaN(target) || Double.isInfinite(target)) {
            target = MIN;
        } else {
            target = Math.max(MIN, Math.min(max, target));
        }
        double tgt = Math.log(target) / Math.log(2.0);
        if (Double.isNaN(targetAnchor) || Math.abs(tgt - targetAnchor) >= DEADBAND_EV) {
            targetAnchor = tgt;
        }
        double cur = Math.log(value) / Math.log(2.0);
        if (Double.isNaN(cur) || Double.isInfinite(cur)) {
            cur = Math.log(MIN) / Math.log(2.0);
        }
        double diff = targetAnchor - cur;
        if (Math.abs(diff) < 1.0e-5) {
            return;
        }
        double tau = diff > 0 ? TAU_BRIGHTEN : TAU_DARKEN;
        float safeDt = Float.isNaN(dt) || Float.isInfinite(dt) ? 1.0f / 60.0f : Math.max(0.0f, Math.min(dt, 0.25f));
        double k = 1.0 - Math.exp(-safeDt / tau);
        double step = diff * k;
        double slew = (diff > 0 ? SLEW_UP_EV : SLEW_DOWN_EV) * safeDt;
        step = Math.max(-slew, Math.min(slew, step));
        cur += step;
        double nextVal = Math.pow(2.0, cur);
        if (Double.isNaN(nextVal) || Double.isInfinite(nextVal)) {
            value = MIN;
            targetAnchor = Double.NaN;
        } else {
            value = (float) Math.max(MIN, Math.min(max, nextVal));
        }
    }

    /** Exposure compensation in EV (settings: path_exposure_ev, tenths) as a linear factor. */
    public static float evScale(int tenthsEv) {
        return (float) Math.pow(2.0, Math.max(-40, Math.min(40, tenthsEv)) / 10.0);
    }

    /**
     * Exposure used by the tone mapping: auto (value() times the compensation) or fixed 1.1 times the compensation. The result is
     * what goes to the GPU, the sky conversion and the compose pass, so every consumer sees the same number.
     */
    public float applied(boolean auto, int evTenths) {
        return (auto ? value() : MIN) * evScale(evTenths);
    }

    public void reset() {
        meterLog2 = Double.NaN;
        value = MIN;
        targetAnchor = Double.NaN;
    }
}
