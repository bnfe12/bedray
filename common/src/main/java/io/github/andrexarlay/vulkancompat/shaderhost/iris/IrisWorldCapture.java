package io.github.andrexarlay.vulkancompat.shaderhost.iris;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads the raw world state for {@link IrisWorldUniforms} on the render thread, once per frame, with the same game calls
 * Iris 1.11.7 makes on Minecraft 26.3 (camera environment attributes, default clock, rain level, light at the eye, mob
 * effects) and the frame's own fog data from the camera render state. A value that cannot be read is reported by its
 * uniform name; the bridge then refuses only programs that read it, never substitutes a default.
 */
public final class IrisWorldCapture {
    /** A sample plus the uniform names that could not be read this frame. */
    public record Result(IrisWorldUniforms.Sample sample, Set<String> unavailable) {
        public Result {
            unavailable = Collections.unmodifiableSet(new LinkedHashSet<>(unavailable));
        }
    }

    private IrisWorldCapture() {}

    public static Result capture(CameraRenderState camera) {
        Set<String> missing = new LinkedHashSet<>();
        Minecraft mc = Minecraft.getInstance();
        var level = mc.level;
        if (level == null) {
            missing.addAll(io.github.andrexarlay.vulkancompat.shaderhost.glsl.IrisUniforms.WORLD.keySet());
            return new Result(null, missing);
        }
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Entity cameraEntity = mc.getCameraEntity();

        long clock = 0;
        boolean fixed = false, endOrNether = false;
        try {
            clock = level.getDefaultClockTime();
            endOrNether = level.dimension() == Level.END || level.dimension() == Level.NETHER;
            fixed = level.dimensionType().hasFixedTime();
        } catch (RuntimeException e) { missing.add("worldTime"); missing.add("worldDay"); }

        int moonPhase = 0;
        float sun = 0, moon = 0;
        float[] sky = {0, 0, 0};
        try {
            var probe = mc.gameRenderer.mainCamera().attributeProbe();
            moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partialTick).index();
            sun = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partialTick);
            moon = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partialTick);
            if (cameraEntity != null) {
                Vector3fc c = probe.getValue(EnvironmentAttributes.SKY_COLOR, partialTick);
                sky = new float[] {c.x(), c.y(), c.z()};
            }
        } catch (RuntimeException e) {
            for (String n : new String[] {"moonPhase", "sunAngle", "shadowAngle", "sunPosition", "moonPosition", "shadowLightPosition", "skyColor"})
                missing.add(n);
        }

        float rain = 0;
        try { rain = level.getRainLevel(partialTick); }
        catch (RuntimeException e) { missing.add("rainStrength"); missing.add("wetness"); }

        float[] fogColor = {0, 0, 0};
        float fogStart = 0, fogEnd = 0;
        if (camera == null || camera.fogData == null || camera.fogData.color == null) {
            missing.add("fogColor"); missing.add("fogStart"); missing.add("fogEnd");
        } else {
            fogColor = new float[] {camera.fogData.color.x(), camera.fogData.color.y(), camera.fogData.color.z()};
            fogStart = camera.fogData.environmentalStart;
            fogEnd = camera.fogData.environmentalEnd;
        }

        int eyeInWater = 0;
        try {
            FogType fluid = mc.gameRenderer.mainCamera().getFluidInCamera();
            boolean spectator = mc.player != null && mc.player.isSpectator();
            eyeInWater = fluid == FogType.WATER ? 1 : !spectator && fluid == FogType.LAVA ? 2 : fluid == FogType.POWDER_SNOW ? 3 : 0;
        } catch (RuntimeException e) { missing.add("isEyeInWater"); }

        int blockLight = 0, skyLight = 0;
        try {
            if (cameraEntity != null) {
                Vec3 pos = cameraEntity.position();
                BlockPos eye = BlockPos.containing(new Vec3(pos.x, cameraEntity.getEyeY(), pos.z));
                blockLight = level.getBrightness(LightLayer.BLOCK, eye);
                skyLight = level.getBrightness(LightLayer.SKY, eye);
            }
        } catch (RuntimeException e) { missing.add("eyeBrightness"); missing.add("eyeBrightnessSmooth"); }

        float nightVision = 0, blindness = 0, darkness = 0;
        try {
            boolean visionFailed = false;
            if (cameraEntity instanceof LivingEntity living) {
                float scale = 0;
                try { scale = GameRenderer.nightVisionScale(living, partialTick); }
                catch (NullPointerException e) { visionFailed = true; } // Iris returns 0 here, without the conduit check
                if (scale > 0) nightVision = Math.max(0, Math.min(1, scale));
                MobEffectInstance blind = living.getEffect(MobEffects.BLINDNESS);
                if (blind != null) blindness = blind.isInfiniteDuration() ? 1f : Math.max(0, Math.min(1, blind.getDuration() / 20f));
                MobEffectInstance dark = living.getEffect(MobEffects.DARKNESS);
                if (dark != null) darkness = dark.getBlendFactor(living, partialTick);
            }
            if (!visionFailed && nightVision == 0 && mc.player != null && mc.player.hasEffect(MobEffects.CONDUIT_POWER)) {
                float water = mc.player.getWaterVision();
                if (water > 0) nightVision = Math.max(0, Math.min(1, water));
            }
        } catch (RuntimeException e) { missing.add("nightVision"); missing.add("blindness"); missing.add("darknessFactor"); }

        return new Result(new IrisWorldUniforms.Sample(clock, fixed, endOrNether, moonPhase, sun, moon, rain, sky, fogColor,
            fogStart, fogEnd, eyeInWater, blockLight, skyLight, nightVision, blindness, darkness), missing);
    }
}
