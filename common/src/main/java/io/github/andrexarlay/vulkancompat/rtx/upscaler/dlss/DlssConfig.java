package io.github.andrexarlay.vulkancompat.rtx.upscaler.dlss;

import io.github.andrexarlay.vulkancompat.rtx.config.UpscalerSelection;
import io.github.andrexarlay.vulkancompat.rtx.upscaler.xess.XessConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Early (pre-device) DLSS request. The Vulkan device is created before any mod config exists and DLSS needs device
 * extensions, so the request comes from {@code -Dvkbridge.dlss=<mode>} ({@link DlssQuality}) or, when absent, from the
 * read-only early parse of {@code config/bedray.toml} ({@code active_backend=DLSS}, {@code dlss_mode}), see
 * {@link UpscalerSelection#earlyDlssQuality(Path)}.
 * <p>
 * XeSS and DLSS are mutually exclusive: when XeSS is requested too, XeSS wins and the DLSS request is reported as failed
 * (one temporal upscaler owns the world target). This class does not touch Minecraft classes.
 */
public final class DlssConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Bedray/DlssConfig");

    public static final String PROPERTY = "vkbridge.dlss";
    /** Jitter sign experiment switches (verifier tunes them on a static scene): {@code -Dvkbridge.dlss.jitterSignX/Y=1|-1}. */
    public static final String JITTER_SIGN_X_PROPERTY = "vkbridge.dlss.jitterSignX";
    public static final String JITTER_SIGN_Y_PROPERTY = "vkbridge.dlss.jitterSignY";
    /** Same relative path as VulkanRtxConfig (the launcher starts the game in the instance directory). */
    private static final Path CONFIG_FILE = io.github.andrexarlay.vulkancompat.rtx.config.ConfigFiles.resolve(Path.of("config", "bedray.toml"));

    private static boolean resolved;
    private static DlssQuality requested;

    private DlssConfig() {}

    /** Requested mode, or {@code null}: not requested, invalid (logged as FAILED) or XeSS is requested instead. */
    public static synchronized DlssQuality requestedQuality() {
        if (!resolved) {
            resolved = true;
            DlssQuality candidate = null;
            String source = "property";
            String raw = System.getProperty(PROPERTY);
            if (raw != null && !raw.isBlank()) {
                try {
                    candidate = DlssQuality.parse(raw);
                } catch (IllegalArgumentException e) {
                    LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=config result=invalid_mode value={} message={}", raw, e.getMessage());
                }
            } else {
                source = "config";
                candidate = UpscalerSelection.earlyDlssQuality(CONFIG_FILE);
            }
            if (candidate != null && XessConfig.requestedQuality() != null) {
                LOGGER.error("[VKB-DLSS] [VKB-AUTOTEST] dlss=FAILED stage=config result=xess_requested (XeSS and DLSS are mutually exclusive, XeSS wins)");
                candidate = null;
            }
            requested = candidate;
            if (requested != null) {
                LOGGER.info("[VKB-DLSS] [VKB-AUTOTEST] dlss=requested mode={} source={}", requested, source);
            }
        }
        return requested;
    }

    /** Test hook: forget the cached request. */
    public static synchronized void resetForTests() {
        resolved = false;
        requested = null;
    }

    /** Jitter sign applied to the pixel jitter handed to NGX (default 1: the guide says "jitter applied to the projection"). */
    public static float jitterSignX() {
        return signProperty(JITTER_SIGN_X_PROPERTY, 1.0f);
    }

    public static float jitterSignY() {
        return signProperty(JITTER_SIGN_Y_PROPERTY, 1.0f);
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
}
