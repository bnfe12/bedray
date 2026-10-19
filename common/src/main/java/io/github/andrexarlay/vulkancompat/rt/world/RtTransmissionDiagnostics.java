package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.Locale;

/**
 * Honest diagnostic snapshot of the RGB colored transmission pipeline.
 *
 * <p>The current implementation uses {@code ANGULAR_LAYER_APPROXIMATION}:
 * a single-layer Beer–Lambert attenuation based on the surface normal dot product
 * and the glass/ice albedo color. This is NOT physically correct volume
 * entry/exit transport — it does not track refraction ray segments through
 * a closed volume.</p>
 *
 * <p>Volume exit transport ({@code VOLUME_EXIT_MISSING}) and caustics ({@code OFF})
 * are honestly reported as not implemented.</p>
 */
public final class RtTransmissionDiagnostics {

    /** The transmission model currently in use. Never claim physical volume transport. */
    public static final String MODEL = "ANGULAR_LAYER_APPROXIMATION";

    private static volatile boolean sunlightActive = false;
    private static volatile boolean localLightsActive = false;
    private static volatile boolean entityLightingActive = false;
    private static volatile int emitterCount = 0;
    private static volatile int entityPrimitives = 0;

    private RtTransmissionDiagnostics() {}

    /**
     * Called from the RT frame renderer after each frame with current emitter and entity state.
     */
    public static void updateFromFrame(int emitters, int entityTris) {
        emitterCount = Math.max(0, emitters);
        entityPrimitives = Math.max(0, entityTris);
        // Sunlight is always active when RT is running (sun shadow ray)
        sunlightActive = true;
        // Local lights only when emitter list is non-empty
        localLightsActive = emitters > 0;
        // Entity lighting only when entity primitives > 0
        entityLightingActive = entityTris > 0;
    }

    public static void reset() {
        sunlightActive = false;
        localLightsActive = false;
        entityLightingActive = false;
        emitterCount = 0;
        entityPrimitives = 0;
    }

    public static boolean isSunlightActive() { return sunlightActive; }
    public static boolean isLocalLightsActive() { return localLightsActive; }
    public static boolean isEntityLightingActive() { return entityLightingActive; }

    /**
     * Formats the canonical transmission diagnostics line. Example:
     * {@code [RT-TRANSMISSION] model=ANGULAR_LAYER_APPROXIMATION sunlight=ACTIVE localLights=ACTIVE
     * entityLighting=INACTIVE volumeExit=VOLUME_EXIT_MISSING caustics=OFF}
     */
    public static String formatDiagnostics() {
        return String.format(Locale.ROOT,
            "[RT-TRANSMISSION] model=%s sunlight=%s localLights=%s entityLighting=%s" +
            " volumeExit=VOLUME_EXIT_MISSING caustics=OFF",
            MODEL,
            sunlightActive ? "ACTIVE" : "INACTIVE",
            localLightsActive ? "ACTIVE" : "INACTIVE",
            entityLightingActive ? "ACTIVE" : "INACTIVE"
        );
    }
}
