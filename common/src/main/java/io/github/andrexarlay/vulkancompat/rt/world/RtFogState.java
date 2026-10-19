package io.github.andrexarlay.vulkancompat.rt.world;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Fog of the game for the RT frame. The ray traced image used to ignore the engine fog completely (distance fog came from an
 * unrelated "ray distance" setting), so the edge of the loaded world was visible and weather / water fog did not exist. This class
 * collects what {@code FogRenderer.setupFog} would give the rasteriser (signatures: {@code javap -p -c -cp minecraft-26.3-client.jar
 * net.minecraft.client.renderer.fog.FogRenderer / FogData / environment.AtmosphericFogEnvironment / WaterFogEnvironment}):
 * <ul>
 *   <li>environmental fog (spherical): {@code EnvironmentAttributes.FOG_START_DISTANCE / FOG_END_DISTANCE} of the camera probe; in rain
 *       the start moves by {@code -160 * rain} and the end by {@code -256 * rain} but not below {@code min(96, end)}
 *       (AtmosphericFogEnvironment.setupFog); with the camera in water {@code WATER_FOG_START/END_DISTANCE} and {@code WATER_FOG_COLOR};</li>
 *   <li>render distance fog (cylindrical, max of horizontal and vertical distance): end = render distance in blocks, start = end -
 *       clamp(end / 10, 4, 64) (FogRenderer.setupFog: bipush 16, fdiv 10, clamp 4..64). Here the end is also limited to the RT radius,
 *       so the edge of the traced world is always inside the fog.</li>
 * </ul>
 * The rain amount is estimated from the sky rain brightness (1 = clear): the engine's own smoothed rain fog multiplier is private state
 * of the fog environment and advancing it from here would change the vanilla fog. The values are written to the frame slot at
 * {@link #OFFSET} (StatsBuf fogVanilla / fogMisc / fogMedium in rt_common.glsl) and mirrored by {@link #linear} / {@link #transmittance} for tests.
 */
public final class RtFogState {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-RT");
    public static final int OFFSET = 416;

    public float envStart = 0f;
    public float envEnd = 1.0e6f;
    public float rdStart = 0f;
    public float rdEnd = 1.0e6f;
    public float rain;
    /** 0 = air, 1 = water. Lava and powder snow keep the air values (no thick fog is invented for them). */
    public int medium;
    public final float[] mediumColor = {0.05f, 0.2f, 0.3f};
    private long lastLogNs;

    /** Linear fog factor of vanilla ({@code fog.glsl linear_fog_value}). */
    public static float linear(float d, float start, float end) {
        if (d <= start) {
            return 0f;
        }
        if (d >= end) {
            return 1f;
        }
        return (d - start) / Math.max(end - start, 1.0e-3f);
    }

    /** Render distance fog interval for a render distance of {@code blocks}: [blocks - clamp(blocks / 10, 4, 64), blocks]. */
    public static float renderDistanceStart(float blocks) {
        return blocks - Math.max(4f, Math.min(64f, blocks / 10f));
    }

    /** Rain adjustment of the environmental start of AtmosphericFogEnvironment.setupFog. */
    public static float rainStart(float start, float rain) {
        return start - 160f * rain;
    }

    /** Rain adjustment of the environmental end: never closer than min(96, end). */
    public static float rainEnd(float end, float rain) {
        return Math.max(Math.min(96f, end), end - 256f * rain);
    }

    /** Vanilla fog transmittance of a point at spherical distance {@code d} and cylindrical distance {@code cyl}. */
    public static float transmittance(float d, float cyl, float envStart, float envEnd, float rdStart, float rdEnd, float weight) {
        float f = Math.max(linear(d, envStart, envEnd), linear(cyl, rdStart, rdEnd)) * Math.max(0f, Math.min(1f, weight));
        return 1f - Math.max(0f, Math.min(1f, f));
    }

    /** Collects the fog of this frame. {@code rainBrightness}: SkyRenderState.rainBrightness (1 = clear). */
    public void collect(Minecraft mc, float partialTick, int radiusXzSections, float rainBrightness) {
        rain = Math.max(0f, Math.min(1f, 1f - rainBrightness));
        float renderBlocks = mc.options.getEffectiveRenderDistance() * 16f;
        float rtBlocks = Math.max(2, radiusXzSections) * 16f;
        rdEnd = Math.min(renderBlocks, rtBlocks);
        rdStart = renderDistanceStart(rdEnd);
        envStart = 0f;
        envEnd = 1.0e6f;
        medium = 0;
        Camera camera = mc.gameRenderer.mainCamera();
        if (camera == null || camera.attributeProbe() == null) {
            return;
        }
        FogType type = camera.getFluidInCamera();
        if (type == FogType.WATER) {
            Float ws = camera.attributeProbe().getValue(EnvironmentAttributes.WATER_FOG_START_DISTANCE, partialTick);
            Float we = camera.attributeProbe().getValue(EnvironmentAttributes.WATER_FOG_END_DISTANCE, partialTick);
            Vector3fc wc = camera.attributeProbe().getValue(EnvironmentAttributes.WATER_FOG_COLOR, partialTick);
            if (ws != null && we != null && wc != null) {
                medium = 1;
                envStart = ws;
                envEnd = we;
                mediumColor[0] = wc.x();
                mediumColor[1] = wc.y();
                mediumColor[2] = wc.z();
            }
            return;
        }
        if (type != FogType.NONE && type != FogType.ATMOSPHERIC) {
            return;   // lava / powder snow: not modelled
        }
        Float s = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_START_DISTANCE, partialTick);
        Float e = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_END_DISTANCE, partialTick);
        if (s != null && e != null) {
            envStart = rainStart(s, rain);
            envEnd = rainEnd(e, rain);
        }
    }

    /** Writes the frame slot fields (offset {@link #OFFSET} of {@code slot}). */
    public void write(long slot, float vanillaWeight, float aerial, float fadeSeconds) {
        long b = slot + OFFSET;
        MemoryUtil.memPutFloat(b, envStart);
        MemoryUtil.memPutFloat(b + 4, envEnd);
        MemoryUtil.memPutFloat(b + 8, rdStart);
        MemoryUtil.memPutFloat(b + 12, rdEnd);
        MemoryUtil.memPutFloat(b + 16, rain);
        MemoryUtil.memPutFloat(b + 20, medium);
        MemoryUtil.memPutFloat(b + 24, vanillaWeight);
        MemoryUtil.memPutFloat(b + 28, aerial);
        MemoryUtil.memPutFloat(b + 32, mediumColor[0]);
        MemoryUtil.memPutFloat(b + 36, mediumColor[1]);
        MemoryUtil.memPutFloat(b + 40, mediumColor[2]);
        MemoryUtil.memPutFloat(b + 44, 0f);
        // fogFade (offset 464): x = clock of the section fade-in (RtWorld.nowTick), y = fade length in ticks (0 = off)
        MemoryUtil.memPutFloat(b + 48, RtWorld.nowTick());
        MemoryUtil.memPutFloat(b + 52, fadeSeconds * 1000f / RtWorld.TICK_MS);
        MemoryUtil.memPutFloat(b + 56, (float) RtWorld.nowTick());
        float waving = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.wavingFoliage()
            ? (float) io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.wavingFoliageStrength()
            : 0f;
        MemoryUtil.memPutFloat(b + 60, waving);
        long now = System.nanoTime();
        if (now - lastLogNs > 2_000_000_000L) {
            lastLogNs = now;
            LOGGER.info(String.format(Locale.ROOT, "[VKB-ATMO] fog env=%.1f..%.1f render_distance=%.1f..%.1f rain=%.2f medium=%s weight=%.2f aerial=%.2f",
                envStart, envEnd, rdStart, rdEnd, rain, medium == 1 ? "water" : "air", vanillaWeight, aerial));
        }
    }
}
