
// Vanilla face shade baked into the Sodium vertex colour (RtLighting.cardinalShade), for a unit normal.
float cardinalShade(vec3 n) {
    return n.y * n.y * (n.y > 0.0 ? 1.0 : 0.5) + n.x * n.x * 0.6 + n.z * n.z * 0.8;
}

// ---- Sky light model (RtSky.skyLight mirrors it) ----------------------------------------------------------------------------
// The sky is the second light of every surface next to the sun/moon key light. Its colour follows the real position of the sun
// in the world (StatsBuf.skySunDir.y, not the key light: at night the key is the moon):
//   noon        clean neutral-cool sky dome, no blue wash (0.25 of the vanilla zenith colour is mixed in, like before)
//   golden hour horizon turns warm orange, zenith keeps a soft violet-blue, the whole dome gets dimmer
//   twilight    short violet-blue afterglow below the horizon
//   night       dark blue moon/star light whose strength follows the moon phase (never fully black)
// Overworld only: dimensions without a skybox keep the neutral constant ambient.
struct SkyLight {
    vec3 zenith;    // irradiance colour from straight above
    vec3 horizon;   // irradiance colour from the horizon band
    vec3 ground;    // bounce colour from below
};

SkyLight skyLight() {
    SkyLight r;
    StatsBuf sb = StatsBuf(pc.statsAddr);
    float day = pc.keyDir.w;
    // Biome identity. The vanilla sky colour carries the biome hue and the fog colour (swamp, badlands, ocean, mushroom
    // fields) is the strongest biome signal the level exposes. At the previous 0.25 weight the neutral base dominated and
    // two Overworld biomes under the same sun looked identical, so the biome share is now close to half.
    vec3 skyTint = 0.45 * pc.skyColor.rgb + 0.55;
    vec3 groundAlbedo = vec3(0.45, 0.43, 0.40);                                   // RtSky.GROUND_COLOR
    if (((sb.skyTexFlags >> 4u) & 1u) == 0u) {
        if (skyIsNether()) {
            vec3 biomeFog = sb.skyFogColor.rgb;
            float netherAmbient = 0.45;
            r.zenith = (biomeFog * 0.75 + 0.05) * netherAmbient;
            r.horizon = (biomeFog * 0.90 + 0.05) * netherAmbient;
            r.ground = mix(groundAlbedo, biomeFog, 0.5) * (netherAmbient * 0.85);
            return r;
        }
        if (skyIsEnd()) {
            vec3 endFog = sb.skyFogColor.rgb;
            vec3 cosmicTint = vec3(0.09, 0.06, 0.14);
            vec3 endAmbient = mix(cosmicTint, endFog, 0.35);
            r.zenith = endAmbient * 0.42;
            r.horizon = endAmbient * 0.32;
            r.ground = mix(groundAlbedo, endAmbient, 0.4) * 0.25;
            return r;
        }
        float scale = 0.65 * (pow(max(0.0, day), 1.5) * 0.90 + 0.01);
        r.zenith = skyTint * scale;
        r.horizon = skyTint * scale;
        r.ground = groundAlbedo * scale;
        return r;
    }
    float se = clamp(sb.skySunDir.y, -1.0, 1.0);                                  // sun elevation, sin(angle)
    float rain = clamp(sb.skyMisc.w, 0.0, 1.0);                                   // 1 clear .. 0 full rain
    float moonUp = smoothstep(-0.05, 0.35, sb.skyMoonDir.y);
    float moonPhase = clamp(sb.skyMisc.z, 0.0, 1.0);

    // daylight dome
    float golden = 1.0 - smoothstep(0.05, 0.50, se);                              // 1 at the horizon .. 0 above ~30 degrees
    vec3 zenithDay = mix(vec3(0.90, 0.94, 1.00), skyTint, 0.5);                   // soft blue-white (RtSky.ZENITH_DAY)
    // The haze band takes the biome fog colour: that is what vanilla tints for swamps, badlands and oceans, and it is the
    // only in-scattered light the horizon has.
    vec3 horizonDay = mix(vec3(1.00, 0.97, 0.92), sb.skyFogColor.rgb * 1.15, 0.30); // warm white haze at noon
    vec3 zenithGold = vec3(0.70, 0.66, 0.90);                                     // violet-blue overhead at sunset
    vec3 horizonGold = vec3(1.00, 0.58, 0.32);                                    // orange glow at the horizon
    vec3 zenithD = mix(zenithDay, zenithGold, golden * 0.8);
    vec3 horizonD = mix(horizonDay, horizonGold, golden * 0.9);
    // overcast: grey, less contrast between dome and horizon
    float over = 1.0 - rain;
    float lum = dot(zenithD, vec3(0.299, 0.587, 0.114));
    zenithD = mix(zenithD, vec3(lum), 0.6 * over);
    horizonD = mix(horizonD, vec3(dot(horizonD, vec3(0.299, 0.587, 0.114))), 0.7 * over);
    float dayScale = 0.42 * pow(max(0.0, day), 1.5);                              // boosted daytime sky ambient so ground/shade stays readable

    // twilight afterglow and night (moon / stars)
    float twilight = smoothstep(-0.28, -0.04, se) * (1.0 - smoothstep(-0.04, 0.10, se));
    float night = (0.022 + 0.040 * moonPhase * moonUp) * (1.0 - day);             // RtSky.NIGHT_LEVEL
    vec3 nightZ = vec3(0.30, 0.40, 0.70);
    vec3 nightH = vec3(0.34, 0.42, 0.66);
    vec3 twZ = vec3(0.36, 0.34, 0.74) * 0.040 * twilight;
    vec3 twH = vec3(0.80, 0.38, 0.44) * 0.036 * twilight;

    r.zenith = zenithD * dayScale + nightZ * night + twZ;
    r.horizon = horizonD * dayScale + nightH * night + twH;
    // ground bounce: albedo lit by the same key light, so sunset sand glows warm and moonlit ground stays cool
    vec3 keyN = pc.keyColor.rgb / max(max(pc.keyColor.r, max(pc.keyColor.g, pc.keyColor.b)), 1.0e-3);
    vec3 groundTint = mix(vec3(1.0), keyN, 0.6);
    r.ground = groundAlbedo * groundTint * (dayScale * 1.15 + 0.5 * night) + 0.5 * (twZ + twH) * 0.5;
    return r;
}

// Hemisphere ambient for a surface normal with vertical component ny: ground bounce below, sky above (RtSky.ambient).
vec3 hemisphereAmbient(float ny, float day) {
    SkyLight l = skyLight();
    float k = 0.5 + 0.5 * clamp(ny, -1.0, 1.0);
    vec3 sky = mix(l.horizon, l.zenith, clamp(ny, 0.0, 1.0));
    return mix(l.ground, sky, k);
}

// Radiance of the sky seen by a secondary (bounce) ray, without the sun disk (the sun is sampled explicitly, NEE).
// Calibrated so that an unoccluded upward surface receives the same irradiance as hemisphereAmbient(1, day) * PI:
// a cosine-weighted estimate of sky radiance L gives albedo * mean(L), which equals albedo * hemisphereAmbient for an open sky.
vec3 skyRadiance(vec3 dir) {
    SkyLight l = skyLight();
    if (dir.y < 0.0) {
        return l.ground;
    }
    float h = clamp(dir.y, 0.0, 1.0);
    return mix(l.horizon, l.zenith, pow(h, 0.6));
}

// Environment radiance of a glossy reflection: the real sky (clouds, stars, moon body) while the lobe is still narrow, the
// calibrated gradient dome at high roughness. The key body is subtracted because the GGX lobe samples the sun/moon
// analytically; without the subtraction a rough metal would gain the disk on top of the highlight.
vec3 skyEnvRadiance(vec3 dir, float roughness) {
    float w = smoothstep(0.08, 0.35, roughness);
    if (w <= 0.0) {
        return max(skyAt(dir) - sunDisk(dir), vec3(0.0));   // smooth lobe: the gradient dome has zero weight
    }
    if (w >= 1.0) {
        return skyRadiance(dir);                            // rough lobe: the real sky has zero weight
    }
    vec3 real = max(skyAt(dir) - sunDisk(dir), vec3(0.0));
    return mix(real, skyRadiance(dir), w);
}

const float PI = 3.14159265358979323846;

// Henyey-Greenstein anisotropic phase function for Mie in-scattering
// p(theta, g) = (1 - g^2) / (4 * pi * (1 + g^2 - 2g * cosTheta)^1.5)
float phaseHenyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denom = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * denom * sqrt(max(denom, 1.0e-5)));
}

float traceVisibility(vec3 origin, vec3 d, float tmax);   // defined below

// Height profile of the air fog density, after the Bedrock RTX fog definition canon (volumetric.density.air with
// uniform = false: full density below max_density_height, none above zero_density_height; NVIDIA texturing guide example
// 56 / 70, here 62 (sea level) / 128 for Java terrain) with a 15 % floor so that valleys and peaks both keep some haze.
float fogHeightProfile(float y) {
    return 0.15 + 0.85 * (1.0 - smoothstep(62.0, 128.0, y));
}

// ---- distance fog of a hit (vanilla fog + aerial perspective) --------------------------------------------------------------
// Vanilla (FogRenderer.setupFog, javap minecraft-26.3-client.jar; values collected by RtFogState): linear fog factor
//   max(linear(sphericalDistance, environmentalStart, environmentalEnd), linear(max(|xz|, |y|), renderDistanceStart, renderDistanceEnd))
// the second term hides the edge of the loaded world, the first one is the biome / weather / water fog. Weight fogMisc.z.
// Aerial perspective on top (air only): Beer-Lambert with a density tied to the render distance (so a short render distance does not
// get a thicker haze than a long one), the height profile of fogHeightProfile, rain up to 4x denser, a low sun 1.5x. The in-scattered
// colour is the vanilla horizon fog colour of the direction (the sky shows the same colour at the horizon: no seam) with a warm
// forward lobe of the key light, so the haze is not a flat blue veil.
const float AERIAL_K = 0.7;            // optical depth at the render distance edge, before density / height / weather: 0.7 -> 50 % transmittance
const float AERIAL_START = 32.0;       // blocks without any haze in front of the camera

// Fade-in of new world sections out of the haze: the primary hit of a section younger than fogFade.y ticks is blended towards the fog
// colour by (1 - smoothstep(age / length)); set by the shaders at the primary hit (gSectionFade), used by fogTransmittanceHit so
// the blend is part of the same linear fog (out = colour * T + A) as the rest of the distance fog. Section birth: Section.origin.w bits 8..31.
float gSectionFade = 0.0;

float sectionFade(uint slot) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (sb.fogFade.y < 1.0 || isEntityHit(slot)) {
        return 0.0;
    }
    uint birth = (uint(sections.s[slot].origin.w) >> 8u) & 0xFFFFFFu;
    uint now = uint(sb.fogFade.x) & 0xFFFFFFu;
    int age = int((now - birth) & 0xFFFFFFu);
    if (age >= 0x800000) {
        age = 0;   // stamped after this frame's clock was read: just born
    }
    return 1.0 - smoothstep(0.0, sb.fogFade.y, float(age));
}

float fogLinear(float d, float s, float e) {
    if (d <= s) {
        return 0.0;
    }
    if (d >= e) {
        return 1.0;
    }
    return (d - s) / max(e - s, 1.0e-3);
}

float fogWeatherDensity() {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    float lowSun = 1.0 - smoothstep(0.0, 0.45, pc.keyDir.y);
    return (1.0 + 3.0 * clamp(sb.fogMisc.x, 0.0, 1.0)) * (1.0 + 0.5 * lowSun * pc.keyDir.w);
}

float fogTransmittanceHit(vec3 origin, vec3 dir, float dist) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    vec3 rel = origin + dir * dist;
    float cyl = max(length(rel.xz), abs(rel.y));
    float fv = max(fogLinear(dist, sb.fogVanilla.x, sb.fogVanilla.y), fogLinear(cyl, sb.fogVanilla.z, sb.fogVanilla.w)) * sb.fogMisc.z;
    float T = 1.0 - clamp(fv, 0.0, 1.0);
    T *= 1.0 - gSectionFade;
    if (sb.fogMisc.y < 0.5 && sb.fogMisc.w > 0.0) {
        float R = max(sb.fogVanilla.w, 96.0) * max(sb.fogDistanceScale, 0.2);
        float yMid = sb.skyCamY + origin.y + dir.y * dist * 0.5;
        float tau = AERIAL_K * sb.fogMisc.w * sb.fogDensity * max(dist - AERIAL_START, 0.0) / R * fogHeightProfile(yMid) * fogWeatherDensity();
        T *= exp(-tau);
    }
    return clamp(T, 0.0, 1.0);
}

vec3 fogRadiance(vec3 dir) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (sb.fogMisc.y > 0.5) {
        return displayToRadiance(sb.fogMedium.rgb);
    }
    vec3 c = displayToRadiance(skyHasSky() ? skyFogColor(dir) : sb.skyFogColor.rgb);
    float forward = phaseHenyeyGreenstein(dot(dir, pc.keyDir.xyz), 0.55) * 4.0 * PI * 0.12;   // 0.12 at the sun direction .. ~0.005 away
    return c + pc.keyColor.rgb * forward * (1.0 - smoothstep(0.0, 0.05, -pc.keyDir.y));
}

// Atmosphere of a camera ray (origin = camera-relative ray origin, dist = hit distance):
//  1. aerial haze on geometry: Beer-Lambert extinction with the height profile, in-scattered colour = the vanilla fog /
//     horizon colour of this direction (so distant terrain fades into the sky horizon like vanilla);
//  2. VOLUMETRIC mode: god rays = single scattering of the key light (sun / moon) along the first 96 blocks of the view ray,
//     6 jittered steps with one shadow ray each (traceVisibility), Henyey-Greenstein phase g = 0.75 (Bedrock default for
//     air, Microsoft Learn "Fog in Resource Packs"), scattering 0.006 / block x density x height profile. The jitter changes
//     every frame; the temporal accumulation / denoiser averages it.
// The sky on a miss already holds the vanilla horizon fog (rt_sky.glsl), so only the god rays are added there.
vec3 applyVolumetricAtmosphere(vec3 color, vec3 origin, vec3 dir, float dist, bool hit) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    uint mode = sb.fogMode;
    if (mode == 0u || !hasFlag(FLAG_VOLUMETRIC_FOG)) {
        return color; // OFF
    }
    float densityFactor = sb.fogDensity;
    if (densityFactor <= 0.0) {
        return color;
    }
    float inscatterFactor = sb.fogInscattering;
    float camY = sb.skyCamY;

    if (hit) {
        float T = fogTransmittanceHit(origin, dir, dist);
        color = color * T + fogRadiance(dir) * (1.0 - T);
    }

    if (mode != 2u) {
        return color;
    }
    vec3 L = pc.keyDir.xyz;
    vec3 Lc = pc.keyColor.rgb;
    if (max(Lc.r, max(Lc.g, Lc.b)) < 1.0e-4 || L.y < -0.02) {
        return color;
    }
    const uint GOD_STEPS = 6u;
    float tEnd = min(hit ? dist : 1.0e6, 96.0);
    float dt = tEnd / float(GOD_STEPS);
    uint hsh = skyHash(uvec3(gl_GlobalInvocationID.xy, pc.frame));
    float jitter = float(hsh & 65535u) / 65536.0;
    float sigS = 0.006 * densityFactor;
    float acc = 0.0;
    for (uint i = 0u; i < GOD_STEPS; i++) {
        float t = (float(i) + jitter) * dt;
        vec3 p = origin + dir * t;
        float dens = sigS * fogHeightProfile(camY + p.y);
        float tr = exp(-dens * t);
        float vis = traceVisibility(p, L, 512.0);
        acc += dens * tr * vis * dt;
    }
    float phase = phaseHenyeyGreenstein(dot(dir, L), 0.75);
    return color + Lc * (acc * phase * 4.0 * PI * 0.25) * inscatterFactor;
}

// Transmittance of applyVolumetricAtmosphere for a surface at dist: it is linear, out = color * T + A, with A =
// applyVolumetricAtmosphere(vec3(0)) (distance fog inscatter + god rays). Used to move the fog attenuation onto the irradiance term.
float atmosphereTransmittance(vec3 origin, vec3 dir, float dist, bool hit) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (!hit || sb.fogMode == 0u || !hasFlag(FLAG_VOLUMETRIC_FOG) || sb.fogDensity <= 0.0) {
        return 1.0;
    }
    return fogTransmittanceHit(origin, dir, dist);
}

// Backwards-compatible wrapper
vec3 applyAtmosphericFog(vec3 surfaceColor, vec3 rayDir, float dist, bool isHit) {
    return applyVolumetricAtmosphere(surfaceColor, vec3(0.0), rayDir, dist, isHit);
}

// Tone mapping: rt_tonemap.glsl (peak-channel filmic curve, RtTonemap.java mirrors it).
vec3 finishColor(vec3 color) {
    if (((pc.modeBits >> 17u) & 1u) != 0u) {
        // RtLighting.EXPOSURE 1.1; the path tracer can use its auto exposure (never below 1.1)
        float exposure = StatsBuf(pc.statsAddr).exposure;   // CPU: auto or fixed 1.1, times the EV compensation
        if (isnan(exposure) || isinf(exposure) || exposure <= 0.0) {
            exposure = 1.1;
        }
        return tonemapColor(color * exposure);
    }
    return color;
}

