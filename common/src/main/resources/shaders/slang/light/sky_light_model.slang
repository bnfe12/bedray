// Sky light model (GI ambient) for Bedray 2.0.
//
// Port of the SkyLight block of the legacy `rt_common_light.glsl` (skyLight,
// hemisphereAmbient, skyRadiance): the three-colour hemisphere the path tracer
// and the diffuse GI use as the second light of every surface next to the
// sun/moon key light.  This replaces the simplified two-colour GradientSky for
// ambient purposes; the full 456-line display sky model (`rt_sky.glsl`: stars,
// clouds, celestial bodies, sky textures) needs a SkyRenderState contract that
// does not exist yet and is NOT ported here — see the notes.
//
// Behaviour is the legacy one:
//   noon         clean neutral-cool sky dome, biome hue mixed into the base
//   golden hour  warm orange horizon, violet-blue zenith, dimmer dome
//   twilight     short violet-blue afterglow below the horizon
//   night        dark blue moon/star light whose strength follows the moon
//                phase (never fully black)
//   overcast     grey, less contrast between dome and horizon
//   Nether/End   flat atmospheric presets from the biome fog colour
//
// Inputs the legacy read from StatsBuf/pc map to explicit parameters; the two
// the contracts cannot supply yet (rain, moon phase) are parameters too, so
// the module stays binding-free and the gaps stay visible to the host.

public module sky_light_model;

import "contracts/math.slang";

// Dimension variants of the legacy skyTexFlags branch.
enum SkyDimension : uint {
    Overworld = 0u,
    Nether    = 1u,
    End       = 2u,
};

struct SkyLightInput {
    float3 sunDirection;     // towards the sun; .y drives golden hour / twilight / night
    float  dayFactor;        // 0 night .. 1 day (legacy keyDir.w)
    float3 moonDirection;    // towards the moon; .y drives the moon-rise term
    float  moonPhase;        // 0..1 (legacy skyMisc.z); contract gap: push param
    float  rain;             // 0 clear .. 1 rain (legacy skyMisc.w); contract gap: push param
    float3 biomeFog;         // biome fog colour, display space (legacy skyFogColor.rgb)
    float3 keyColor;         // rgb radiance of the key light; its hue tints the ground bounce
    SkyDimension dimension;
};

struct SkyLight {
    float3 zenith;           // irradiance colour from straight above
    float3 horizon;          // irradiance colour from the horizon band
    float3 ground;           // bounce colour from below
};

// Legacy ground albedo (RtSky.GROUND_COLOR).
static const float3 SKY_GROUND_ALBEDO = float3(0.45, 0.43, 0.40);

SkyLight skyLightModel(SkyLightInput input) {
    SkyLight r;

    if (input.dimension == SkyDimension.Nether) {
        // Nether: rich atmospheric biome fog, no day cycle.
        float netherAmbient = 0.45;
        r.zenith = (input.biomeFog * 0.75 + 0.05) * netherAmbient;
        r.horizon = (input.biomeFog * 0.90 + 0.05) * netherAmbient;
        r.ground = lerp(SKY_GROUND_ALBEDO, input.biomeFog, 0.5) * (netherAmbient * 0.85);
        return r;
    }
    if (input.dimension == SkyDimension.End) {
        // End: cosmic violet mixed with the biome fog.
        float3 cosmicTint = float3(0.09, 0.06, 0.14);
        float3 endAmbient = lerp(cosmicTint, input.biomeFog, 0.35);
        r.zenith = endAmbient * 0.42;
        r.horizon = endAmbient * 0.32;
        r.ground = lerp(SKY_GROUND_ALBEDO, endAmbient, 0.4) * 0.25;
        return r;
    }

    // Overworld.
    float day = clamp(input.dayFactor, 0.0, 1.0);
    float se = clamp(input.sunDirection.y, -1.0, 1.0);   // sun elevation, sin(angle)
    float rain = clamp(input.rain, 0.0, 1.0);
    float moonUp = smoothstep(-0.05, 0.35, input.moonDirection.y);
    float moonPhase = clamp(input.moonPhase, 0.0, 1.0);

    // Biome identity: the vanilla sky colour carries the biome hue; the legacy
    // weight kept the neutral base dominant (0.45 biome) so two Overworld
    // biomes under the same sun stay distinguishable but calm.
    float3 skyTint = 0.45 * input.biomeFog + 0.55;

    float golden = 1.0 - smoothstep(0.05, 0.50, se);   // 1 at the horizon .. 0 above ~30 degrees
    float3 zenithDay = lerp(float3(0.90, 0.94, 1.00), skyTint, 0.5);
    float3 horizonDay = lerp(float3(1.00, 0.97, 0.92), input.biomeFog * 1.15, 0.30);
    float3 zenithGolden = float3(0.70, 0.66, 0.90);    // violet-blue overhead at sunset
    float3 horizonGolden = float3(1.00, 0.58, 0.32);   // orange glow at the horizon
    float3 zenithD = lerp(zenithDay, zenithGolden, golden * 0.8);
    float3 horizonD = lerp(horizonDay, horizonGolden, golden * 0.9);

    // Overcast: grey, less contrast between dome and horizon.
    // The new input is 0=clear and 1=rain, opposite to the old skyMisc.w
    // clear-weather factor. Desaturate with increasing rain.
    float over = rain;
    float zenithLuminance = dot(zenithD, float3(0.299, 0.587, 0.114));
    zenithD = lerp(zenithD, float3(zenithLuminance), 0.6 * over);
    float horizonLuminance = dot(horizonD, float3(0.299, 0.587, 0.114));
    horizonD = lerp(horizonD, float3(horizonLuminance), 0.7 * over);
    float dayScale = 0.42 * pow(max(0.0, day), 1.5);   // boosted daytime ambient so ground/shade stays readable

    // Twilight afterglow and night (moon / stars).
    float twilight = smoothstep(-0.28, -0.04, se) * (1.0 - smoothstep(-0.04, 0.10, se));
    float night = (0.022 + 0.040 * moonPhase * moonUp) * (1.0 - day);
    float3 nightZenith = float3(0.30, 0.40, 0.70);
    float3 nightHorizon = float3(0.34, 0.42, 0.66);
    float3 twilightZenith = float3(0.36, 0.34, 0.74) * 0.040 * twilight;
    float3 twilightHorizon = float3(0.80, 0.38, 0.44) * 0.036 * twilight;

    r.zenith = zenithD * dayScale + nightZenith * night + twilightZenith;
    r.horizon = horizonD * dayScale + nightHorizon * night + twilightHorizon;
    // Ground bounce: albedo lit by the same key light, so sunset sand glows
    // warm and moonlit ground stays cool.  The legacy tint is the key colour
    // normalised by its brightest channel.
    float keyMaximum = max(input.keyColor.r, max(input.keyColor.g, input.keyColor.b));
    float3 keyNormalized = keyMaximum > 1.0e-3 ? input.keyColor / keyMaximum : float3(1.0);
    float3 groundTint = lerp(float3(1.0), keyNormalized, 0.6);
    r.ground = SKY_GROUND_ALBEDO * groundTint * (dayScale * 1.15 + 0.5 * night) +
        0.5 * (twilightZenith + twilightHorizon) * 0.5;
    return r;
}

// Hemisphere ambient for a surface normal with vertical component ny: ground
// bounce below, sky above.  `day` is kept for signature parity with the
// legacy helper; the model carries the day factor itself.
float3 hemisphereAmbient(SkyLight light, float normalY) {
    float k = 0.5 + 0.5 * clamp(normalY, -1.0, 1.0);
    float3 sky = lerp(light.horizon, light.zenith, clamp(normalY, 0.0, 1.0));
    return lerp(light.ground, sky, k);
}

// Sky radiance seen by a secondary (bounce) ray, without the sun disk: the
// sun/moon key light is sampled explicitly by NEE, so an escaped ray must not
// add it twice.  Calibrated so an unoccluded upward surface receives the same
// irradiance as hemisphereAmbient(1) * PI.
float3 skyRadiance(SkyLight light, float3 direction) {
    if (direction.y < 0.0) return light.ground;
    float h = clamp(direction.y, 0.0, 1.0);
    return lerp(light.horizon, light.zenith, pow(h, 0.6));
}
