package io.github.andrexarlay.vulkancompat.rt.world;

/**
 * Pure sky/key-light model of the RT shading (unit-tested). The shader mirrors {@link #ambient} and uses the key light
 * direction/colour computed here.
 *
 * <p>Sun direction, confirmed from the bytecode of SkyRenderer.renderSunMoonAndStars (javap -c on libs/minecraft-26.3-client.jar):
 * poseStack.rotateDegrees(YP, -90); rotate(XP, sunAngle) (moon: moonAngle); the sprite is then translated to local (0, 100, 0)
 * (applyCelestialBodyTransform(100, 30) for the sun, (100, 20) for the moon). Ry(-90) * Rx(a) * (0,1,0) = {@code (-sin a, cos a, 0)}:
 * angle 0 is noon, the sun is at +X (east) before noon and sets towards -X. The angles are radians (SkyRenderState.sunAngle).
 * {@code -Dvkbridge.rt.sunFlipX=true} stays as a debugging override.
 */
public final class RtSky {
    /** Key light of the frame: direction towards the light, colour (linear, already scaled), day factor 0..1. */
    public static final class Key {
        public final float[] dir = {0f, 1f, 0f};
        public final float[] color = {0f, 0f, 0f};
        public float day;
        /** True when the moon is the key light (sun more than ~7 degrees below the horizon). */
        public boolean isMoon;
        /**
         * Alpha vanilla draws the celestial sprite with, which is {@code SkyRenderState.rainBrightness} itself: the bytecode of
         * SkyRenderer.renderSunMoonAndStars passes field rainBrightness straight into renderSun, where it becomes
         * {@code new Vector4f(1, 1, 1, rainBrightness)} (javap -c on libs/minecraft-26.3-client.jar, 2026-10-26). Anything that
         * only exists because the body is in the sky — the glare halo, the specular glint on water and glass — has to obey it,
         * or a pouring storm still shows a sun. The key colour cannot stand in for it: it keeps a weather floor.
         */
        public float bodyAlpha;
    }

    private RtSky() {
    }

    /**
     * Tilt of the celestial arc (radians) about the east-west axis, set every frame from RtSettings.sunTiltRad (sun_tilt_deg).
     * Vanilla passes the sun through the zenith at noon, where nothing casts a readable shadow and every vertical face is
     * unlit; a tilt towards the south (+Z) lowers the noon sun to {@code 90 - tilt} degrees like a real latitude. Sun, moon
     * and stars use it together (rt_sky.glsl celestialLocal applies the inverse), so the sky disk and the light stay aligned.
     * 0 = vanilla geometry (default of the pure model; the tests rely on it).
     */
    public static volatile float tiltRad = 0f;

    public static void sunDirection(float angleRadians, boolean flipX, float[] out) {
        float s = (float) Math.sin(angleRadians);
        float c = (float) Math.cos(angleRadians);
        float t = tiltRad;
        out[0] = flipX ? s : -s;
        out[1] = c * (float) Math.cos(t);
        out[2] = c * (float) Math.sin(t);
    }

    public static float smoothstep(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / (e1 - e0)));
        return t * t * (3f - 2f * t);
    }

    /** 0 at night, 1 in full daylight, from the sun elevation (sin of the angle above the horizon). */
    public static float dayFactor(float sunY) {
        return smoothstep(-0.10f, 0.25f, sunY);
    }

    /**
     * Strength of the sun glare halo: the settings strength times the day factor, the smoothstep of the elevation (the halo
     * fades out as the body sets) and {@link Key#bodyAlpha} (the storm that hides the sun hides its halo too).
     * Every factor is clamped, so a caller cannot push the term out of range.
     */
    public static float glareStrength(float strength, float day, float elevation, float bodyAlpha) {
        float e = Math.max(0f, Math.min(1f, elevation));
        return strength * Math.max(0f, Math.min(1f, day)) * (e * e * (3f - 2f * e))
            * Math.max(0f, Math.min(1f, bodyAlpha));
    }

    /**
     * Chooses the key light. {@code hasSky=false} (Nether, no skybox) gives no directional light and a constant day factor.
     * The moon takes over when the sun is more than ~7 degrees below the horizon.
     */
    public static void keyLight(float sunAngle, float moonAngle, boolean flipX, boolean hasSky, Key out) {
        keyLight(sunAngle, moonAngle, flipX, hasSky, 1f, 1f, 0f, out);
    }

    /** Key light without thunder data (callers that test the rain / phase behaviour in isolation). */
    public static void keyLight(float sunAngle, float moonAngle, boolean flipX, boolean hasSky, float moonLight,
                                float rainBrightness, Key out) {
        keyLight(sunAngle, moonAngle, flipX, hasSky, moonLight, rainBrightness, 0f, out);
    }

    /**
     * Sun colour (linear) by elevation (sin of the angle above the horizon): a Kelvin-like ramp from the red-orange of the horizon
     * through the golden hour to a neutral warm white at noon. Rows: elevation, r, g, b. The intensity is applied separately.
     */
    private static final float[][] SUN_RAMP = {
        {-0.12f, 1.00f, 0.26f, 0.08f},
        {0.00f, 1.00f, 0.54f, 0.18f},
        {0.06f, 1.00f, 0.72f, 0.30f},
        {0.14f, 1.00f, 0.84f, 0.45f},
        {0.28f, 1.00f, 0.92f, 0.68f},
        {0.50f, 1.00f, 0.96f, 0.84f},
        {0.80f, 1.00f, 0.98f, 0.94f},
    };
    /** Sun colour at noon and near the horizon (ends of {@link #SUN_RAMP}). */
    public static final float[] SUN_HIGH = {1.00f, 0.98f, 0.94f};
    public static final float[] SUN_LOW = {1.00f, 0.54f, 0.18f};
    /** Peak sun intensity (linear) at noon with a clear sky; the day factor and the weather scale it down. */
    public static final float SUN_INTENSITY = 1.45f;

    /** Writes the sun colour for an elevation into {@code out} (unscaled, brightest channel 1). */
    public static void sunColor(float elevation, float[] out) {
        float[][] r = SUN_RAMP;
        if (elevation <= r[0][0]) {
            out[0] = r[0][1]; out[1] = r[0][2]; out[2] = r[0][3];
            return;
        }
        for (int i = 1; i < r.length; i++) {
            if (elevation <= r[i][0]) {
                float t = (elevation - r[i - 1][0]) / (r[i][0] - r[i - 1][0]);
                for (int c = 0; c < 3; c++) {
                    out[c] = r[i - 1][c + 1] + (r[i][c + 1] - r[i - 1][c + 1]) * t;
                }
                return;
            }
        }
        float[] last = r[r.length - 1];
        out[0] = last[1]; out[1] = last[2]; out[2] = last[3];
    }

    /** Full moon light (linear, before the phase / weather factors): cold, weak; torches and the sky glow dominate at night. */
    public static final float[] MOON_COLOR = {0.062f, 0.077f, 0.105f};

    /**
     * Key light with the moon phase light ({@code moonLight}, 1 = full moon .. ~0.08 = new moon, RtSkyState) and the vanilla
     * rain brightness ({@code SkyRenderState.rainBrightness}, 1 = clear, 0 = full rain) applied.
     */
    public static void keyLight(float sunAngle, float moonAngle, boolean flipX, boolean hasSky, float moonLight,
                                float rainBrightness, float thunder, Key out) {
        if (!hasSky) {
            out.dir[0] = 0f; out.dir[1] = 1f; out.dir[2] = 0f;
            out.color[0] = 0f; out.color[1] = 0f; out.color[2] = 0f;
            out.day = 0.6f;
            out.isMoon = false;
            out.bodyAlpha = 0f;
            return;
        }
        float[] sun = new float[3];
        float[] moon = new float[3];
        sunDirection(sunAngle, flipX, sun);
        sunDirection(moonAngle, flipX, moon);
        out.day = dayFactor(sun[1]);
        out.isMoon = sun[1] <= -0.12f;
        float rain = Math.max(0f, Math.min(1f, rainBrightness));
        out.bodyAlpha = rain;
        // Vanilla's own storm factor (Level.getSkyDarken): a thunderstorm darkens the skylight on top of the rain. Applied to
        // the key light, so the sky, the sun/moon colour and every traced shadow term follow it.
        float storm = 1f - (5f / 16f) * Math.max(0f, Math.min(1f, thunder));
        float weather = (0.25f + 0.75f * rain) * storm;
        if (sun[1] > -0.12f) {
            System.arraycopy(sun, 0, out.dir, 0, 3);
            float k = SUN_INTENSITY * out.day * weather;
            float[] c = new float[3];
            sunColor(sun[1], c);
            // overcast sky: the direct light loses its warmth (clouds scatter it to a neutral white)
            float grey = 0.55f * (1f - rain);
            float lum = 0.299f * c[0] + 0.587f * c[1] + 0.114f * c[2];
            for (int i = 0; i < 3; i++) {
                out.color[i] = (c[i] + (lum - c[i]) * grey) * k;
            }
        } else {
            System.arraycopy(moon, 0, out.dir, 0, 3);
            float nightFade = smoothstep(0.12f, 0.28f, -sun[1]);
            float k = nightFade * smoothstep(0.0f, 0.2f, moon[1]) * Math.max(0f, moonLight) * weather;
            out.color[0] = MOON_COLOR[0] * k;
            out.color[1] = MOON_COLOR[1] * k;
            out.color[2] = MOON_COLOR[2] * k;
        }
    }

    /** Ground bounce albedo of the hemisphere ambient: neutral warm earth tone. */
    public static final float[] GROUND_COLOR = {0.45f, 0.43f, 0.40f};
    /** Sky irradiance scale at full daylight (pow(day, 1.5) is applied); the sun carries more of the picture now. */
    public static final float AMBIENT_SCALE = 0.30f;
    /** Night sky light: stars and a moonless sky (never black) plus the moon phase / elevation part. */
    public static final float NIGHT_BASE = 0.022f;
    public static final float NIGHT_MOON = 0.040f;

    /** Day part of the sky light scale: {@code AMBIENT_SCALE * pow(day, 1.5)}. The night sky light is {@link #nightLevel}. */
    public static float ambientScale(float day) {
        return AMBIENT_SCALE * (float) Math.pow(Math.max(0f, day), 1.5);
    }

    /** Night sky light level: {@code (NIGHT_BASE + NIGHT_MOON * moonPhaseLight * moonUp) * (1 - day)}. */
    public static float nightLevel(float day, float moonPhaseLight, float moonY) {
        float moonUp = smoothstep(-0.05f, 0.35f, moonY);
        return (NIGHT_BASE + NIGHT_MOON * Math.max(0f, Math.min(1f, moonPhaseLight)) * moonUp) * (1f - Math.max(0f, Math.min(1f, day)));
    }

    /** Sky light colours of the frame (irradiance, already scaled): zenith, horizon, ground bounce. */
    public static final class SkyLight {
        public final float[] zenith = new float[3];
        public final float[] horizon = new float[3];
        public final float[] ground = new float[3];
    }

    private static float mixf(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /**
     * Mirror of {@code skyLight()} in rt_common.glsl. {@code sunY}/{@code moonY} are the vertical components of the sun / moon
     * directions, {@code rain} the vanilla rain brightness (1 clear), {@code keyColor} the key light of the frame.
     * The GPU path also tints the horizon haze with the biome fog colour; it is the one term this mirror lacks because the
     * fog colour is not an argument here.
     */
    public static void skyLight(float[] skyColor, float day, float sunY, float moonY, float moonPhaseLight, float rain,
                                float[] keyColor, boolean hasSky, SkyLight out) {
        float[] skyTint = new float[3];
        for (int i = 0; i < 3; i++) {
            skyTint[i] = 0.45f * skyColor[i] + 0.55f;
        }
        if (!hasSky) {
            float scale = 0.65f * ((float) Math.pow(Math.max(0f, day), 1.5) * 0.90f + 0.01f);
            for (int i = 0; i < 3; i++) {
                out.zenith[i] = skyTint[i] * scale;
                out.horizon[i] = skyTint[i] * scale;
                out.ground[i] = GROUND_COLOR[i] * scale;
            }
            return;
        }
        float se = Math.max(-1f, Math.min(1f, sunY));
        rain = Math.max(0f, Math.min(1f, rain));
        float golden = 1f - smoothstep(0.05f, 0.50f, se);
        float[] zenithDay = {0.90f, 0.94f, 1.00f};
        float[] horizonDay = {1.00f, 0.97f, 0.92f};
        float[] zenithGold = {0.70f, 0.66f, 0.90f};
        float[] horizonGold = {1.00f, 0.58f, 0.32f};
        float over = 1f - rain;
        float[] zD = new float[3];
        float[] hD = new float[3];
        for (int i = 0; i < 3; i++) {
            zD[i] = mixf(mixf(zenithDay[i], skyTint[i], 0.5f), zenithGold[i], golden * 0.8f);
            hD[i] = mixf(horizonDay[i], horizonGold[i], golden * 0.9f);
        }
        float zl = 0.299f * zD[0] + 0.587f * zD[1] + 0.114f * zD[2];
        float hl = 0.299f * hD[0] + 0.587f * hD[1] + 0.114f * hD[2];
        for (int i = 0; i < 3; i++) {
            zD[i] = mixf(zD[i], zl, 0.6f * over);
            hD[i] = mixf(hD[i], hl, 0.7f * over);
        }
        float dayScale = ambientScale(day);
        float twilight = smoothstep(-0.28f, -0.04f, se) * (1f - smoothstep(-0.04f, 0.10f, se));
        float night = nightLevel(day, moonPhaseLight, moonY);
        float[] nightZ = {0.30f, 0.40f, 0.70f};
        float[] nightH = {0.34f, 0.42f, 0.66f};
        float[] twZ = {0.36f * 0.040f * twilight, 0.34f * 0.040f * twilight, 0.74f * 0.040f * twilight};
        float[] twH = {0.80f * 0.036f * twilight, 0.38f * 0.036f * twilight, 0.44f * 0.036f * twilight};
        float keyMax = Math.max(1.0e-3f, Math.max(keyColor[0], Math.max(keyColor[1], keyColor[2])));
        for (int i = 0; i < 3; i++) {
            out.zenith[i] = zD[i] * dayScale + nightZ[i] * night + twZ[i];
            out.horizon[i] = hD[i] * dayScale + nightH[i] * night + twH[i];
            float groundTint = mixf(1f, keyColor[i] / keyMax, 0.6f);
            out.ground[i] = GROUND_COLOR[i] * groundTint * (dayScale * 0.9f + 0.5f * night) + 0.5f * (twZ[i] + twH[i]) * 0.5f;
        }
    }

    /**
     * Hemisphere ambient for a surface normal with vertical component {@code ny}
     * (same expression as {@code hemisphereAmbient} in the shader, overworld sky).
     */
    public static void ambient(float[] skyColor, float day, float sunY, float moonY, float moonPhaseLight, float rain,
                               float[] keyColor, float ny, float[] out) {
        SkyLight l = new SkyLight();
        skyLight(skyColor, day, sunY, moonY, moonPhaseLight, rain, keyColor, true, l);
        float k = 0.5f + 0.5f * Math.max(-1f, Math.min(1f, ny));
        float up = Math.max(0f, Math.min(1f, ny));
        for (int i = 0; i < 3; i++) {
            float sky = mixf(l.horizon[i], l.zenith[i], up);
            out[i] = mixf(l.ground[i], sky, k);
        }
    }

    /**
     * Evaluates square sun/moon coverage (0..1) for a ray direction {@code dir} towards {@code keyDir}.
     * Half-size is 0.15 for sun (day), 0.10 for moon (night) at distance 100.
     */
    public static float celestialSquareCoverage(float[] dir, float[] keyDir, boolean isDay) {
        float dL = dir[0] * keyDir[0] + dir[1] * keyDir[1] + dir[2] * keyDir[2];
        if (dL <= 0f) return 0f;
        float ux = -keyDir[1];
        float uy = keyDir[0];
        float uz = 0f;
        float ulen = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (ulen > 1e-6f) {
            ux /= ulen; uy /= ulen; uz /= ulen;
        } else {
            ux = 1f; uy = 0f; uz = 0f;
        }
        // V = cross(keyDir, U)
        float vx = keyDir[1] * uz - keyDir[2] * uy;
        float vy = keyDir[2] * ux - keyDir[0] * uz;
        float vz = keyDir[0] * uy - keyDir[1] * ux;

        float u = (dir[0] * ux + dir[1] * uy + dir[2] * uz) / dL;
        float v = (dir[0] * vx + dir[1] * vy + dir[2] * vz) / dL;
        float halfSize = isDay ? 0.15f : 0.10f;
        float d = Math.max(Math.abs(u), Math.abs(v));
        return 1f - smoothstep(halfSize, halfSize + 0.003f, d);
    }

    /**
     * Evaluates procedural Minecraft-style blocky cloud density (0..1) for a ray direction {@code dir}
     * matching {@code skyClouds} in rt_common.glsl.
     */
    public static float cloudsCoverage(float[] dir, int frame) {
        if (dir[1] <= 0.02f) return 0f;
        float h = Math.max(dir[1], 0.03f);
        float skyPosX = (dir[0] / h) * 32.0f;
        float skyPosZ = (dir[2] / h) * 32.0f;
        float drift = frame * 0.015f;
        skyPosX += drift;
        skyPosZ += drift * 0.2f;

        float cellX = (float) Math.floor(skyPosX);
        float cellZ = (float) Math.floor(skyPosZ);

        double dot1 = cellX * 127.1 + cellZ * 311.7;
        float h1 = (float) (Math.abs(Math.sin(dot1) * 43758.5453123) % 1.0);

        double dot2 = Math.floor(cellX * 0.25) * 269.5 + Math.floor(cellZ * 0.25) * 183.3;
        float h2 = (float) (Math.abs(Math.sin(dot2) * 43758.5453123) % 1.0);

        double dot3 = Math.floor(cellX * 0.0625) * 419.2 + Math.floor(cellZ * 0.0625) * 371.9;
        float h3 = (float) (Math.abs(Math.sin(dot3) * 43758.5453123) % 1.0);

        float coverage = h1 * 0.2f + h2 * 0.4f + h3 * 0.4f;
        float density = smoothstep(0.48f, 0.52f, coverage);
        float horizonFade = smoothstep(0.02f, 0.15f, dir[1]);
        return density * horizonFade * 0.85f;
    }

    /**
     * Minecraft camera angles that look along {@code dir}: yaw 0 = south (+Z), 90 = west (-X), 180 = north, -90 = east;
     * pitch +90 = straight down. Returns {yawDegrees, pitchDegrees}.
     */
    public static float[] aimYawPitch(float[] dir) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dir[0], dir[2]));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1f, Math.min(1f, dir[1]))));
        return new float[] {yaw, pitch};
    }

    /**
     * Henyey-Greenstein anisotropic phase function for Mie in-scattering:
     * p(theta, g) = (1 - g^2) / (4 * pi * (1 + g^2 - 2g * cosTheta)^1.5)
     */
    public static float henyeyGreensteinPhase(float cosTheta, float g) {
        float g2 = g * g;
        float denom = 1.0f + g2 - 2.0f * g * cosTheta;
        return (1.0f - g2) / (4.0f * (float) Math.PI * denom * (float) Math.sqrt(Math.max(denom, 1.0e-5f)));
    }

    /**
     * Beer-Lambert exponential transmittance:
     * transmittance = exp(-density * distance * sigmaT)
     */
    public static float beerLambertTransmittance(float density, float distance, float sigmaT) {
        return (float) Math.min(1.0, Math.max(0.0, Math.exp(-density * distance * sigmaT)));
    }

    /**
     * Atmospheric volumetric fog matching applyVolumetricAtmosphere in rt_common.glsl.
     */
    public static void volumetricAtmosphere(float[] color, float[] origin, float[] dir, float dist, boolean hit,
                                            float[] keyDir, float[] keyColor, float[] skyColor, float day,
                                            float density, float inScattering, float fogDist,
                                            boolean volumetric, float[] out) {
        if (!volumetric || density <= 0.0f) {
            System.arraycopy(color, 0, out, 0, 3);
            return;
        }
        float maxDist = Math.max(fogDist, 32.0f);
        float sigmaT = 2.5f / maxDist;
        float optDist = hit ? dist : maxDist * (1.0f / Math.max(Math.abs(dir[1]) * 1.5f + 0.3f, 0.3f));

        float yMid = origin[1] + dir[1] * Math.min(optDist, maxDist) * 0.5f;
        float heightFactor = Math.max(0.5f, Math.min(2.0f, (float) Math.exp(-yMid * 0.005f)));

        float transmittance = beerLambertTransmittance(density * heightFactor, optDist, sigmaT);

        // Ambient horizon / sky fog color
        float[] horizon = new float[3];
        for (int i = 0; i < 3; i++) {
            float base = (i == 0) ? 0.85f : (i == 1 ? 0.90f : 1.0f);
            horizon[i] = skyColor[i] + (base - skyColor[i]) * (0.5f * day);
        }
        float h = Math.max(0.0f, Math.min(1.0f, dir[1] * 0.5f + 0.5f));
        float hPow = (float) Math.pow(h, 0.6);
        float[] ambientSky = new float[3];
        for (int i = 0; i < 3; i++) {
            ambientSky[i] = horizon[i] + (skyColor[i] - horizon[i]) * hPow;
        }

        // Night adaptation: deep cinematic night fog, neutral cool tint
        float[] nightFogColor = {0.010f, 0.013f, 0.020f};
        float dayCurve = (float) Math.pow(Math.max(0.0f, day), 1.5) * 0.90f + 0.04f;
        float dayBlend = smoothstep(0.0f, 0.25f, day);
        float[] ambientFog = new float[3];
        for (int i = 0; i < 3; i++) {
            float dayFog = ambientSky[i] * 0.85f;
            ambientFog[i] = (nightFogColor[i] + (dayFog - nightFogColor[i]) * dayBlend) * dayCurve;
        }

        // Anisotropic Mie solar in-scattering via Henyey-Greenstein phase function (g approx 0.65)
        float cosTheta = dir[0] * keyDir[0] + dir[1] * keyDir[1] + dir[2] * keyDir[2];
        float phase = henyeyGreensteinPhase(cosTheta, 0.65f);
        float sunScale = Math.max(day, 0.05f) * (phase * (4.0f * (float) Math.PI * 0.35f)) * inScattering;
        float[] inscattering = new float[3];
        for (int i = 0; i < 3; i++) {
            inscattering[i] = keyColor[i] * sunScale;
        }

        float[] fogColor = new float[3];
        for (int i = 0; i < 3; i++) {
            fogColor[i] = ambientFog[i] + inscattering[i];
            out[i] = color[i] * transmittance + fogColor[i] * (1.0f - transmittance);
        }
    }
}
