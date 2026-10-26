package io.github.andrexarlay.vulkancompat.rt.world;

import java.util.Locale;

/**
 * Diagnostic snapshot of RGB colored shadow-ray transmission, separate from camera/path water transport.
 *
 * <p>The legacy identifier {@code ANGULAR_LAYER_APPROXIMATION} describes approximate RGB layer filtering.
 * Glass uses tint/alpha; ice and water shadow layers also use the incidence angle. These straight shadow
 * rays do not track refracted entry/exit transport through a closed glass volume.</p>
 *
 * <p>{@code VOLUME_EXIT_MISSING} and {@code caustics=OFF} refer to this shadow filter. The separate
 * realistic-water path has Snell refraction, medium segments and optional approximate analytic caustics;
 * it must not be inferred from these legacy shadow fields. See {@link RtModesDebug} for water mode details.</p>
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
