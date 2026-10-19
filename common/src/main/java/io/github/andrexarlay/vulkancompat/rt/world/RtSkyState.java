package io.github.andrexarlay.vulkancompat.rt.world;

import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Vector3fc;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Vanilla sky parameters of the frame for the RT sky (shaders/rt_sky.glsl), all taken from the game's own render state:
 * <ul>
 *   <li>{@code SkyRenderState} (26.3, filled by SkyRenderer.extractRenderState from the EnvironmentAttributes): sunAngle,
 *       moonAngle, starAngle (radians), rainBrightness, starBrightness, sunriseAndSunsetColor, moonPhase, skyColor, skybox;</li>
 *   <li>{@code LevelRenderState.cloudColor} (ARGB int), {@code cloudHeight}, {@code gameTime}, {@code worldPartialTicks}
 *       (javap 26.3 client jar; CloudRenderer uses cloudColor / cloudHeight / gameTime the same way);</li>
 *   <li>{@code EnvironmentAttributes.FOG_COLOR}, {@code SKY_FOG_END_DISTANCE}, {@code CLOUD_FOG_END_DISTANCE} through
 *       {@code Minecraft.gameRenderer.mainCamera().attributeProbe().getValue(attr, partialTicks)} (as
 *       AtmosphericFogEnvironment.getBaseColor / setupFog do);</li>
 *   <li>{@code Options.getCloudStatus()}, {@code Options.cloudRange()}, {@code Options.getEffectiveRenderDistance()}.</li>
 * </ul>
 * Cloud placement mirrors CloudRenderer.render (decompiled 26.3): offset {@code (gameTime % (texW * 400) + partialTicks) * 0.03}
 * blocks along +X, {@code +3.96} along Z, 12 x 4 x 12 block cells, bottom at cloudHeight.
 * <p>
 * Written into the per-frame slot of RtFrameRenderer at {@link #OFFSET} (layout in rt_common.glsl, StatsBuf sky* fields).
 */
public final class RtSkyState {
    private static final Logger LOGGER = LoggerFactory.getLogger("VKB-SKY");
    /** Byte offset of the sky block in the per-frame slot. */
    public static final int OFFSET = 256;
    public static final int BYTES = 128;
    /** Moon light per phase (MoonPhase.index(): full, waning gibbous, third quarter, waning crescent, new, waxing ...). */
    private static final float[] PHASE_LIGHT = {1.0f, 0.75f, 0.5f, 0.25f, 0.08f, 0.25f, 0.5f, 0.75f};

    public final float[] sunDir = new float[3];
    public final float[] moonDir = new float[3];
    public float sunAngle;
    public float moonAngle;
    public float starAngle;
    public float rainBrightness = 1f;
    public float starBrightness;
    public final float[] sunset = new float[4];
    public final float[] fogColor = {0.75f, 0.85f, 1f};
    public final float[] skyColor = {0.47f, 0.65f, 1f};
    public int moonPhase;
    public float moonLight = 1f;
    public boolean hasSky;
    public boolean isEnd;
    public boolean isNether;
    public float endFlashIntensity;
    public float endFlashXAngle;
    public float endFlashYAngle;
    public int cloudArgb;
    public float cloudHeight;
    public int cloudStatus;
    public float cloudEnd;
    public float skyEnd;
    public double cloudX;
    public double cloudY;
    public double cloudZ;
    public long gameTime;
    public double cameraY;
    public long clockTime;
    private long lastLogNs;

    /** Fills the state from the game for this frame. */
    public void collect(LevelRenderState level, double camX, double camY, double camZ, boolean flipX, int cloudTexW, int cloudTexH) {
        SkyRenderState sky = level.skyRenderState;
        Minecraft mc = Minecraft.getInstance();
        hasSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
        isEnd = sky.skybox == DimensionType.Skybox.END;
        isNether = sky.skybox == DimensionType.Skybox.NONE;
        endFlashIntensity = sky.endFlashIntensity;
        endFlashXAngle = sky.endFlashXAngle;
        endFlashYAngle = sky.endFlashYAngle;
        RtSky.tiltRad = io.github.andrexarlay.vulkancompat.rtx.config.RtSettings.sunTiltRad();
        sunAngle = sky.sunAngle;
        moonAngle = sky.moonAngle;
        starAngle = sky.starAngle;
        RtSky.sunDirection(sunAngle, flipX, sunDir);
        RtSky.sunDirection(moonAngle, flipX, moonDir);
        rainBrightness = hasSky ? clamp01(sky.rainBrightness) : 1f;
        starBrightness = hasSky ? Math.max(0f, sky.starBrightness) : 0f;
        Vector4fc ss = sky.sunriseAndSunsetColor;
        if (hasSky && ss != null) {
            sunset[0] = ss.x();
            sunset[1] = ss.y();
            sunset[2] = ss.z();
            sunset[3] = ss.w();
        } else if (isEnd) {
            sunset[0] = endFlashIntensity;
            sunset[1] = endFlashXAngle;
            sunset[2] = endFlashYAngle;
            sunset[3] = 0f;
        } else {
            sunset[0] = sunset[1] = sunset[2] = sunset[3] = 0f;
        }
        moonPhase = hasSky && sky.moonPhase != null ? sky.moonPhase.index() & 7 : 0;
        moonLight = PHASE_LIGHT[moonPhase];
        if (sky.skyColor != null) {
            skyColor[0] = sky.skyColor.x();
            skyColor[1] = sky.skyColor.y();
            skyColor[2] = sky.skyColor.z();
        }
        float pt = level.worldPartialTicks;
        int renderBlocks = mc.options.getEffectiveRenderDistance() * 16;
        Camera camera = mc.gameRenderer.mainCamera();
        skyEnd = renderBlocks;
        cloudEnd = mc.options.cloudRange().get() * 16f;
        if (camera != null && camera.attributeProbe() != null) {
            Vector3fc fog = camera.attributeProbe().getValue(EnvironmentAttributes.FOG_COLOR, pt);
            if (fog != null) {
                fogColor[0] = fog.x();
                fogColor[1] = fog.y();
                fogColor[2] = fog.z();
            }
            Float se = camera.attributeProbe().getValue(EnvironmentAttributes.SKY_FOG_END_DISTANCE, pt);
            if (se != null) {
                skyEnd = Math.min(renderBlocks, se);
            }
            Float ce = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_FOG_END_DISTANCE, pt);
            if (ce != null) {
                cloudEnd = Math.min(cloudEnd, ce);
            }
        }
        cloudArgb = level.cloudColor;
        cloudHeight = level.cloudHeight;
        CloudStatus cs = mc.options.getCloudStatus();
        cloudStatus = !hasSky || cloudTexW <= 0 || ((cloudArgb >>> 24) == 0) ? 0 : cs == CloudStatus.FANCY ? 2 : cs == CloudStatus.FAST ? 1 : 0;
        gameTime = level.gameTime;
        clockTime = mc.level != null ? mc.level.getOverworldClockTime() : 0L;
        if (cloudTexW > 0 && cloudTexH > 0) {
            double offset = (double) (gameTime % (cloudTexW * 400L)) + pt;
            double cx = camX + offset * 0.030000001f;
            double cz = camZ + 3.96f;
            double tw = cloudTexW * 12.0;
            double th = cloudTexH * 12.0;
            cloudX = cx - Math.floor(cx / tw) * tw;
            cloudZ = cz - Math.floor(cz / th) * th;
        }
        cloudY = camY - cloudHeight;
        cameraY = camY;
    }

    /** Writes the sky block at {@code slot + OFFSET} (see StatsBuf in rt_common.glsl). */
    public void write(long slot, long texAddress, boolean flipX) {
        MemoryUtil.memPutFloat(slot + 244, (float) cameraY);   // StatsBuf.skyCamY
        long b = slot + OFFSET;
        MemoryUtil.memPutFloat(b, sunDir[0]);
        MemoryUtil.memPutFloat(b + 4, sunDir[1]);
        MemoryUtil.memPutFloat(b + 8, sunDir[2]);
        MemoryUtil.memPutFloat(b + 12, sunAngle);
        MemoryUtil.memPutFloat(b + 16, moonDir[0]);
        MemoryUtil.memPutFloat(b + 20, moonDir[1]);
        MemoryUtil.memPutFloat(b + 24, moonDir[2]);
        MemoryUtil.memPutFloat(b + 28, moonAngle);
        MemoryUtil.memPutFloat(b + 32, fogColor[0]);
        MemoryUtil.memPutFloat(b + 36, fogColor[1]);
        MemoryUtil.memPutFloat(b + 40, fogColor[2]);
        MemoryUtil.memPutFloat(b + 44, starBrightness);
        MemoryUtil.memPutFloat(b + 48, sunset[0]);
        MemoryUtil.memPutFloat(b + 52, sunset[1]);
        MemoryUtil.memPutFloat(b + 56, sunset[2]);
        MemoryUtil.memPutFloat(b + 60, sunset[3]);
        MemoryUtil.memPutFloat(b + 64, ((cloudArgb >> 16) & 255) / 255f);
        MemoryUtil.memPutFloat(b + 68, ((cloudArgb >> 8) & 255) / 255f);
        MemoryUtil.memPutFloat(b + 72, (cloudArgb & 255) / 255f);
        MemoryUtil.memPutFloat(b + 76, ((cloudArgb >>> 24) & 255) / 255f);
        MemoryUtil.memPutFloat(b + 80, (float) cloudX);
        MemoryUtil.memPutFloat(b + 84, (float) cloudY);
        MemoryUtil.memPutFloat(b + 88, (float) cloudZ);
        MemoryUtil.memPutFloat(b + 92, cloudEnd);
        MemoryUtil.memPutFloat(b + 96, starAngle);
        MemoryUtil.memPutFloat(b + 100, skyEnd);
        MemoryUtil.memPutFloat(b + 104, moonLight);
        MemoryUtil.memPutFloat(b + 108, rainBrightness);
        MemoryUtil.memPutLong(b + 112, texAddress);
        int flags = (moonPhase & 7) | (hasSky ? 1 << 4 : 0) | (flipX ? 1 << 5 : 0)
            | (isEnd ? 1 << 6 : 0) | (isNether ? 1 << 7 : 0) | ((cloudStatus & 3) << 8);
        MemoryUtil.memPutInt(b + 120, flags);
        MemoryUtil.memPutFloat(b + 124, RtSky.tiltRad);   // StatsBuf.skyTilt
    }

    /** Longest auto exposure of the path tracer for this day factor: night stays dark (no eye adaptation to daylight). */
    public static float maxExposure(float day) {
        float d = clamp01(day);
        return RtExposure.MIN * 2.0f + (RtExposure.MAX - RtExposure.MIN * 2.0f) * d * d;
    }

    /** One log line on the first frame and every 10 s: time, sun / moon, brightness, colours, clouds. */
    public void log(RtSky.Key key, float exposure, int texMask) {
        long now = System.nanoTime();
        if (lastLogNs != 0L && now - lastLogNs < 10_000_000_000L) {
            return;
        }
        lastLogNs = now;
        LOGGER.info(String.format(Locale.ROOT,
            "[VKB-SKY] time=%d game_time=%d day=%.2f key=%s key_rgb=(%.3f,%.3f,%.3f) sun_dir=(%.3f,%.3f,%.3f) moon_dir=(%.3f,%.3f,%.3f)"
                + " moon_phase=%d moon_light=%.2f rain_bright=%.2f stars=%.2f sky=(%.3f,%.3f,%.3f) fog=(%.3f,%.3f,%.3f)"
                + " sunset=(%.2f,%.2f,%.2f,%.2f) clouds=%s cloud_argb=%08x cloud_h=%.1f cloud_end=%.0f sky_end=%.0f"
                + " ambient_scale=%.4f exposure=%.2f exposure_max=%.2f tex_mask=0x%x",
            clockTime, gameTime, key.day, key.isMoon ? "moon" : "sun", key.color[0], key.color[1], key.color[2],
            sunDir[0], sunDir[1], sunDir[2], moonDir[0], moonDir[1], moonDir[2], moonPhase, moonLight, rainBrightness,
            starBrightness, skyColor[0], skyColor[1], skyColor[2], fogColor[0], fogColor[1], fogColor[2], sunset[0], sunset[1],
            sunset[2], sunset[3], cloudStatus == 2 ? "fancy" : cloudStatus == 1 ? "fast" : "off", cloudArgb, cloudHeight,
            cloudEnd, skyEnd, RtSky.ambientScale(key.day), exposure, maxExposure(key.day), texMask));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
