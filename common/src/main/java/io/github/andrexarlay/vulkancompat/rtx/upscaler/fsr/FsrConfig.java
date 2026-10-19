package io.github.andrexarlay.vulkancompat.rtx.upscaler.fsr;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss.DlssConfig;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Early (pre-device) FSR request and the FSR experiment switches. No Minecraft classes (unit-tested).
 * <ul>
 * <li>{@code -Dvkrtx.fsr=<mode>}: NATIVE_AA, QUALITY, BALANCED, PERFORMANCE, ULTRA_PERFORMANCE ({@link FsrQuality});
 *     without it the read-only early parse of {@code config/bedray.toml} ({@code active_backend=FSR}, {@code fsr_mode},
 *     {@code fsr_version}) is used.</li>
 * <li>{@code -Dvkrtx.fsr.version=auto|3.1|2}: provider version ({@link FsrVersion}).</li>
 * <li>{@code -Dvkrtx.fsr.sharpness=0..1}: RCAS sharpness override (otherwise the config value).</li>
 * <li>{@code -Dvkrtx.fsr.jitterSignX/Y=1|-1}: sign of the pixel jitter handed to FSR (verifier tunes on a static scene).</li>
 * <li>{@code -Dvkrtx.fsr.debugChecking=true}: FFX_UPSCALE_ENABLE_DEBUG_CHECKING (the runtime reports API misuse through the
 *     message callback, logged as {@code [VKB-FSR] ffx message}).</li>
 * </ul>
 * XeSS, DLSS and FSR are mutually exclusive at startup (one temporal upscaler owns the world target): XeSS or DLSS requested
 * early wins and the FSR request is reported as FAILED.
 */
public final class FsrConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/FsrConfig");

    public static final String PROPERTY = "vkrtx.fsr";
    public static final String VERSION_PROPERTY = "vkrtx.fsr.version";
    public static final String SHARPNESS_PROPERTY = "vkrtx.fsr.sharpness";
    public static final String JITTER_SIGN_X_PROPERTY = "vkrtx.fsr.jitterSignX";
    public static final String JITTER_SIGN_Y_PROPERTY = "vkrtx.fsr.jitterSignY";
    public static final String DEBUG_CHECKING_PROPERTY = "vkrtx.fsr.debugChecking";
    private static final Path CONFIG_FILE = io.github.andrexarlay.vulkancompat.rtx.config.ConfigFiles.resolve(Path.of("config", "bedray.toml"));

    private static boolean resolved;
    private static FsrRequest requested;

    private FsrConfig() {}

    /** The early request, or null (not requested, invalid: logged as FAILED, or XeSS/DLSS requested instead). */
    public static synchronized FsrRequest requestedRequest() {
        if (!resolved) {
            resolved = true;
            FsrRequest candidate = null;
            String source = "property";
            String raw = System.getProperty(PROPERTY);
            if (raw != null && !raw.isBlank()) {
                try {
                    candidate = new FsrRequest(FsrQuality.parse(raw), propertyVersion(FsrVersion.AUTO));
                } catch (IllegalArgumentException e) {
                    LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage=config result=invalid_value value={} message={}", raw, e.getMessage());
                }
            } else {
                source = "config";
                candidate = UpscalerSelection.earlyFsrRequest(CONFIG_FILE);
                if (candidate != null) {
                    candidate = new FsrRequest(candidate.quality(), propertyVersion(candidate.version()));
                }
            }
            if (candidate != null && (XessConfig.requestedQuality() != null || DlssConfig.requestedQuality() != null)) {
                LOGGER.error("[VKB-FSR] [VKB-AUTOTEST] fsr=FAILED stage=config result=other_upscaler_requested (XeSS/DLSS/FSR are mutually exclusive at startup; XeSS or DLSS wins)");
                candidate = null;
            }
            requested = candidate;
            if (requested != null) {
                LOGGER.info("[VKB-FSR] [VKB-AUTOTEST] fsr=requested mode={} version={} source={}", requested.quality(), requested.version(), source);
            }
        }
        return requested;
    }

    /** {@code -Dvkrtx.fsr.version} when set, otherwise {@code fallback}. An invalid value throws (no silent default). */
    public static FsrVersion propertyVersion(FsrVersion fallback) {
        String v = System.getProperty(VERSION_PROPERTY);
        return v == null || v.isBlank() ? fallback : FsrVersion.parse(v);
    }

    /** {@code -Dvkrtx.fsr.sharpness} in [0,1], or {@code fallback} when unset. Out of range or not a number throws. */
    public static float sharpness(float fallback) {
        String v = System.getProperty(SHARPNESS_PROPERTY);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        float f = Float.parseFloat(v.trim());
        if (!(f >= 0.0f && f <= 1.0f)) {
            throw new IllegalArgumentException("-D" + SHARPNESS_PROPERTY + " must be in [0,1], got " + v);
        }
        return f;
    }

    public static boolean debugChecking() {
        return Boolean.getBoolean(DEBUG_CHECKING_PROPERTY);
    }

    /**
     * Sign of the pixel jitter handed to FSR. Default -1: CameraJitterManager adds +2*j/size to projection m20/m21, which
     * moves the projected image by -j pixels (clip.x += m20*z, w = -z), while FSR's jitterOffset is the shift applied to
     * the projection (super-resolution-upscaler.md "Camera jitter"). Same default as XeSS; tuned by the verifier.
     */
    public static float jitterSignX() {
        return signProperty(JITTER_SIGN_X_PROPERTY, -1.0f);
    }

    public static float jitterSignY() {
        return signProperty(JITTER_SIGN_Y_PROPERTY, -1.0f);
    }

    private static float signProperty(String name, float def) {
        String v = System.getProperty(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        float f = Float.parseFloat(v.trim());
        if (f != 1.0f && f != -1.0f) {
            throw new IllegalArgumentException("-D" + name + " must be 1 or -1, got " + v);
        }
        return f;
    }

    /** Test hook. */
    public static synchronized void resetForTests() {
        resolved = false;
        requested = null;
    }
}
