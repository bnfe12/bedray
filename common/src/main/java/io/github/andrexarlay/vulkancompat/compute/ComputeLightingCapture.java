package io.github.andrexarlay.vulkancompat.compute;

import io.github.andrexarlay.bedray.api.v1.LightingFrame;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3fc;

/**
 * Copies the current game's environment into the compute lighting contract without RT runtime state.
 * The game's RGB_COLOR attributes are normalized display RGB values: the vanilla extractor copies
 * them directly and lightmap.fsh writes them to RGBA8_UNORM. Compute explicitly interprets these
 * display tints as sRGB and decodes once for its linear albedo/lighting path. This is a documented
 * presentation approximation, not an exact replacement for vanilla's nonlinear lightmap equation.
 * Block/sky factors remain separate; the consumer applies each exactly once.
 */
final class ComputeLightingCapture {
    private static String pendingReason;
    private ComputeLightingCapture() {}

    static String pendingReason() { return pendingReason; }

    private static LightingFrame pending(String reason) {
        pendingReason = reason;
        return null;
    }

    /** Null means that current-frame lighting is unavailable or invalid; do not reuse an older frame. */
    static LightingFrame collect(GameRenderState state) {
        pendingReason = null;
        // Minecraft 26.3 declares readyForLevelRendering but never populates it. The actual world
        // pass uses shouldRenderLevel; camera/projection readiness is checked by ComputeWorldCapture.
        if (state == null || !state.shouldRenderLevel || state.levelRenderState == null)
            return pending("Waiting for current world render state");
        if (state.lightmapRenderState == null) return pending("Waiting for current lightmap state");
        var light = state.lightmapRenderState;
        var sky = state.levelRenderState.skyRenderState;
        if (sky == null || sky.skybox == null) return pending("Waiting for current dimension sky state");
        if (!validColor(light.blockLightTint) || !validColor(light.skyLightColor)
            || !validColor(light.ambientColor) || !validColor(light.nightVisionColor))
            return pending("Waiting for finite nonnegative lightmap colors");
        if (!validScalar(light.blockFactor) || !validScalar(light.skyFactor)
            || !validScalar(light.brightness) || !validScalar(light.darknessEffectScale)
            || !validScalar(light.nightVisionEffectIntensity)
            || !validScalar(light.bossOverlayWorldDarkening))
            return pending("Invalid lightmap factors: block=" + light.blockFactor + " sky=" + light.skyFactor
                + " brightness=" + light.brightness + " darkness=" + light.darknessEffectScale
                + " nightVision=" + light.nightVisionEffectIntensity + " boss=" + light.bossOverlayWorldDarkening);

        boolean overworld = sky.skybox == DimensionType.Skybox.OVERWORLD;
        if (overworld && (!validColor(sky.skyColor) || !Float.isFinite(sky.sunAngle)
            || !Float.isFinite(sky.moonAngle) || !validScalar(sky.rainBrightness)
            || sky.moonPhase == null || sky.moonPhase.index() < 0 || sky.moonPhase.index() >= 8))
            return pending("Waiting for finite overworld sky color, angles, weather and moon phase");

        float[] values = new float[LightingFrame.FLOAT_COUNT];
        values[1] = 1; // A finite unit direction is also provided when the directional source is disabled.
        values[7] = light.skyFactor;
        values[11] = overworld ? 1 : 0;
        values[15] = light.blockFactor;
        values[19] = light.brightness;
        values[20] = light.darknessEffectScale;
        values[21] = light.nightVisionEffectIntensity;
        values[22] = light.bossOverlayWorldDarkening;
        putLinearColor(values, 12, light.blockLightTint);

        // Preserve vanilla's actual ambient/night-vision color choice before display-to-linear conversion.
        // Slot 21 is informational: this factor is already folded here and must not be applied again.
        for (int channel = 0; channel < 3; channel++) {
            float ambient = light.ambientColor.get(channel);
            float nightVision = light.nightVisionColor.get(channel) * light.nightVisionEffectIntensity;
            values[16 + channel] = linear(Math.max(ambient, nightVision));
        }

        if (overworld) {
            putLinearColor(values, 8, sky.skyColor);
            float sunY = (float) Math.cos(sky.sunAngle);
            float moonY = (float) Math.cos(sky.moonAngle);
            boolean sun = sunY > 0;
            if (sun || moonY > 0) {
                float angle = sun ? sky.sunAngle : sky.moonAngle;
                // SkyRenderer uses Ry(-90 degrees) * Rx(angle) * (0,1,0).
                // Do not use RtSky.sunDirection: its mutable tilt belongs to the hardware renderer.
                values[0] = -(float) Math.sin(angle);
                values[1] = (float) Math.cos(angle);
                values[2] = 0;
                float weather = Math.clamp(sky.rainBrightness, 0, 1);
                // An explicit compute policy: illuminated fraction of the game's eight moon phases.
                // Vanilla's lightmap does not expose a separate physical lunar irradiance value.
                float phase = sun ? 1 : (float) ((1 + Math.cos(sky.moonPhase.index() * Math.PI / 4)) * 0.5);
                float sourceScale = weather * phase;
                values[3] = sourceScale > 0 ? 1 : 0;
                for (int channel = 0; channel < 3; channel++)
                    values[4 + channel] = linear(light.skyLightColor.get(channel)) * sourceScale;
            }
        }
        // Non-overworld analytic sky is disabled; the host's raster image supplies its actual sky.
        for (float value : values) if (!Float.isFinite(value)) return pending("Nonfinite converted world lighting");
        return new LightingFrame(values);
    }

    private static boolean validScalar(float value) { return Float.isFinite(value) && value >= 0; }

    private static boolean validColor(Vector3fc value) {
        return value != null && validScalar(value.x()) && validScalar(value.y()) && validScalar(value.z());
    }

    private static void putLinearColor(float[] values, int offset, Vector3fc color) {
        for (int channel = 0; channel < 3; channel++) values[offset + channel] = linear(color.get(channel));
    }

    private static float linear(float display) {
        return display <= 0.04045f ? display / 12.92f
            : (float) Math.pow((display + 0.055f) / 1.055f, 2.4);
    }
}
