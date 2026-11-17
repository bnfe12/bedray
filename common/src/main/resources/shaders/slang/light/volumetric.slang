// Volumetric light shafts ("god rays") and air fog math for Bedray 2.0.
//
// Port of the legacy GLSL `rt_volumetric.comp` + the fog helpers of
// `rt_common_light.glsl`, rewritten against the Bedray 2.0 contracts: the
// march takes an `ISceneTracer` for the shaft's shadow rays and the host passes
// every input explicitly (there are no bindings and no global state here).
// The two compute entries that drive it live in
// `passes/volumetric_march_scene.slang` (half resolution march + temporal store) and
// `passes/volumetric_apply.slang` (depth-aware bilateral upsample, scene-space
// additive composite).
//
// Behavioural reference and deliberate differences:
//   * SIGMA_S 0.012 per block at full density (the legacy value; Bedrock air is
//     0.006, the pass doubles it so the default strength reads) times the
//     host density multiplier times the height profile.
//   * Two-lobe Henyey-Greenstein phase: forward Mie lobe g = 0.78 (Bedrock
//     default for air) blended with a weak back lobe g = -0.25 at 12 %, plus
//     an isotropic 1/(4*pi) boost of 18 % * darkness so shafts stay readable
//     from the side in dark rooms without washing the screen out.
//   * Weather and time of day: (1 + 1.2 * lowSun) * (1 + 1.4 * rain), the same
//     multipliers the legacy pass used.  `rain` is an explicit parameter: the
//     FrameConstants AtmosphereState has no weather channel yet (listed in the
//     notes as a contract gap).
//   * The shaft march is bounded by the caller's step count (legacy clamps to
//     2..48), one `traceVisibility` shadow ray per step with density > 0.
//
// Everything is a pure function over parameters: no bindings, no #ifdef, no
// GPU-name checks. GPU execution and integration are not verified.

public module volumetric;

import "contracts/frame.slang";
import "contracts/hit.slang";
import "contracts/math.slang";
import "tonemap_curve.slang";
import "traversal/traversal.slang";
import "light/block_emitters.slang";
import "sampling.slang";

// Length of god-ray march steps for short rays, in blocks.
static const float VOLUMETRIC_STEP_BLOCKS = 3.0;

uint volumetricStepCount(float tEnd, uint qualitySteps) {
    uint maxSteps = clamp(qualitySteps, 2u, 48u);
    return clamp(uint(ceil(max(tEnd, 0.0) / VOLUMETRIC_STEP_BLOCKS)), 2u, maxSteps);
}

static const float VOLUMETRIC_SIGMA_S = 0.012;          // scattering per block at full density
static const float VOLUMETRIC_HEIGHT_BASE = 62.0;       // legacy: sea level where the profile starts to fall
static const float VOLUMETRIC_HEIGHT_TOP = 128.0;       // legacy: full-height boundary of the fall
static const float VOLUMETRIC_FLOOR = 0.15;             // density floor so valleys and peaks keep some haze
static const float VOLUMETRIC_MIN_CONTRIBUTION = 1.0e-5;
static const float VOLUMETRIC_SHADOW_RANGE = 512.0;     // shadow-ray far plane of a shaft step

// ---- Shared small helpers ---------------------------------------------------

// Jimenez 2014 interleaved gradient noise, shifted by a golden-ratio sequence
// per frame.  One jitter value per pixel per frame; the temporal accumulation
// averages the rest.
float shaftJitter(float2 pixel, uint frame) {
    float2 p = pixel + 5.588238 * float(frame & 63u);
    return frac(52.9829189 * frac(dot(p, float2(0.06711056, 0.00583715))));
}

float henyeyGreenstein(float cosTheta, float g) {
    float g2 = g * g;
    float denominator = 1.0 + g2 - 2.0 * g * cosTheta;
    return (1.0 - g2) / (4.0 * PI * denominator * sqrt(max(denominator, 1.0e-5)));
}

// Phase of the air: forward Mie lobe plus a weak back lobe, blended towards a
// flat 1/(4*pi) in dark places by `dark` in 0..1.
float volumetricPhase(float cosTheta, float dark) {
    float hg = lerp(henyeyGreenstein(cosTheta, 0.78), henyeyGreenstein(cosTheta, -0.25), 0.12);
    return lerp(hg, 0.0795775, 0.18 * dark);
}

// Density multiplier of the weather and the time of day: thicker air in rain
// and with a low sun (long optical path).  `keyDirY` is the vertical component
// of the direction *towards* the key light.
float volumetricWeather(float keyDirY, float rain) {
    float r = clamp(rain, 0.0, 1.0);
    float lowSun = 1.0 - smoothstep(0.0, 0.45, keyDirY);
    return (1.0 + 1.2 * lowSun) * (1.0 + 1.4 * r);
}

// Height profile of the air density: full below the base height, falling to
// the floor at the top height.  `worldY` is a world-space height (the caller
// adds the camera world height to the camera-relative sample point).
float fogHeightProfile(float worldY) {
    return VOLUMETRIC_FLOOR +
        (1.0 - VOLUMETRIC_FLOOR) * (1.0 - smoothstep(VOLUMETRIC_HEIGHT_BASE, VOLUMETRIC_HEIGHT_TOP, worldY));
}

// Darkness adaptation of the legacy pass: gentle in dark rooms and forests,
// without exploding the density under tree shade.  `exposure` is the frame's
// exposure value (NaN-safe); the smoothstep window is the legacy one.
float volumetricDarkness(float exposure) {
    if (!isFinite(exposure)) return 0.0;
    return smoothstep(1.8, 4.0, exposure);
}

// ---- The shaft march --------------------------------------------------------

// Marches the view ray from 0 to `tEnd` in `steps` jittered steps and returns
// the single-scattered key-light radiance.  One visibility ray per step whose
// density is above the contribution floor.
//
//   origin              view-bob eye offset in raw-camera-relative space
//   dir                 normalized view direction (camera-relative space)
//   tEnd                march length: min(primary hit distance, shaft range)
//   worldHeightOffset   camera world height: added to the relative sample y
//                       before the height profile
//   densityMul          host density * volumetricWeather * darkness term
//   dark                volumetricDarkness of the frame exposure
//   rain                weather parameter (see volumetricWeather)
//
// The loop is bounded by `steps` (the legacy entry clamps 2..48); no
// recursion, no allocation.
float3 marchLightShaft<T : ISceneTracer>(T traversal, float3 origin, float3 dir, float tEnd,
        uint steps, float jitter, float3 keyDir, float3 keyColor,
        float worldHeightOffset, float densityMul, float dark, float inscatter,
        float strength, float rayBias) {
    float3 L = safeNormalize(keyDir, float3(0.0, 1.0, 0.0));
    float3 Lc = keyColor;
    if (max(Lc.r, max(Lc.g, Lc.b)) <= 1.0e-4) return float3(0.0);
    if (L.y <= -0.02) return float3(0.0);          // key light below the horizon
    if (tEnd <= 0.5) return float3(0.0);           // legacy: shafts start half a block out
    if (densityMul <= 0.0) return float3(0.0);
    // Steps follow the ray length (one per VOLUMETRIC_STEP_BLOCKS, at most the quality's count): a shaft ending on a
    // wall 3 blocks away no longer pays the full march.  The per-pixel jitter and the history keep it unbiased.
    uint n = volumetricStepCount(tEnd, steps);
    float dt = tEnd / float(n);

    float transmittance = 1.0;
    float accumulation = 0.0;
    for (uint i = 0u; i < n; ++i) {
        float t = (float(i) + jitter) * dt;
        float3 p = origin + dir * t;
        float density = VOLUMETRIC_SIGMA_S * densityMul *
            fogHeightProfile(worldHeightOffset + p.y);
        transmittance *= exp(-density * dt);
        if (density > VOLUMETRIC_MIN_CONTRIBUTION) {
            Ray shadowRay = makeRay(p, L, rayBias, VOLUMETRIC_SHADOW_RANGE);
            VisibilityResult visibility = traversal.traceVisibility(shadowRay);
            float lit = visibility.visible ? 1.0 : 0.0;
            accumulation += lit * density * transmittance * dt;
        }
    }

    // 4*pi*0.25 = pi: the same normalisation as the legacy in-shader god rays,
    // so the settings keep their look across the migration.
    float3 scatter = Lc * (accumulation * volumetricPhase(dot(dir, L), dark) * PI);
    scatter *= inscatter * strength;
    if (!isFinite(scatter)) return float3(0.0);
    return scatter;
}

// ---- Block-light glow -------------------------------------------------------

// A torch, lantern or glowstone lights the air around it, not only the surfaces it touches. This is the single-scattering
// counterpart of the emitter estimate of the path tracer: one emitter per step is drawn from the same light tree, the same
// d^-1.25 kernel and window are used, and one visibility ray decides whether the air at that step can see the drawn point.
// The sun shafts above only know the key light; without this term a lamp in fog had no halo and no beams.
static const float EMITTER_GLOW_SIGMA = 0.08;        // scattering per block at host density 1.0 (the sun shafts use 0.012)
static const float EMITTER_GLOW_PHASE = 0.0795775;   // isotropic 1 / (4 pi): a lamp scatters evenly in every direction
static const float EMITTER_GLOW_MAX_RANGE = 64.0;    // the march ends here; lamps reach EMITTER_RADIUS from a sample only
static const uint  EMITTER_GLOW_MAX_STEPS = 14u;
static const float EMITTER_GLOW_MAX_ENERGY = 40.0;   // clamp of one sample before scattering: a tiny quad right next to a step

// `table` is the BEM2 emitter list the path tracer reads, `cameraRelative` the camera minus the origin the list was
// published at, `origin` / `dir` the view ray in raw-camera-relative space, `density` the host multiplier of the glow air.
// The hue comes from the albedo texel of the drawn point (quads of captured entities live in an atlas this pass does not
// bind and keep the warm block-light tint). Returns radiance in the same units as the shaft march.
float3 marchEmitterGlow<T : ISceneTracer>(T traversal, StructuredBuffer<uint4> table, Sampler2D albedoAtlas,
        Sampler2D materialAtlas, float3 cameraRelative, float3 origin, float3 dir, float tEnd, uint steps, float jitter, inout uint seed,
        float density, float worldHeightOffset, float gain, float rayBias) {
    if (gain <= 0.0 || density <= 0.0 || tEnd <= 0.5) return float3(0.0);
    uint count = blockEmitterCount(table);
    if (count == 0u) return float3(0.0);
    float range = min(tEnd, EMITTER_GLOW_MAX_RANGE);
    uint n = clamp(steps, 2u, EMITTER_GLOW_MAX_STEPS);
    float dt = range / float(n);

    float transmittance = 1.0;
    float3 accumulation = float3(0.0);
    for (uint i = 0u; i < n; ++i) {
        float t = (float(i) + jitter) * dt;
        float3 p = origin + dir * t;
        float sigma = EMITTER_GLOW_SIGMA * density * fogHeightProfile(worldHeightOffset + p.y);
        transmittance *= exp(-sigma * dt);
        if (sigma <= VOLUMETRIC_MIN_CONTRIBUTION) continue;

        // The light tree is keyed by position in the list's own frame: the sample point shifted by the camera offset.
        BlockEmitterSelection chosen = blockEmitterSelect(table, p + cameraRelative, rand01(seed));
        if (chosen.index >= count || !(chosen.probability > 0.0)) continue;
        BlockEmitterRecord emitter = blockEmitterRecord(table, chosen.index);
        float2 quad = float2(rand01(seed), rand01(seed));
        float3 lightPoint = emitter.corner.xyz - cameraRelative + emitter.edge1.xyz * quad.x + emitter.edge2.xyz * quad.y;
        float3 toLight = lightPoint - p;
        float d2 = dot(toLight, toLight);
        if (d2 > EMITTER_RADIUS * EMITTER_RADIUS || d2 < 1.0e-4) continue;
        float distanceToLight = sqrt(d2);
        float3 L = toLight / distanceToLight;

        float3 crossEdge = cross(emitter.edge1.xyz, emitter.edge2.xyz);
        float crossLength = length(crossEdge);
        float3 emitterNormal = crossLength > 1.0e-6 ? crossEdge / crossLength : float3(0.0, 1.0, 0.0);
        bool twoSided = emitter.edge2.w > 0.5;
        float cosEmitter = dot(emitterNormal, -L);
        float cosGeometry = twoSided ? abs(cosEmitter) : max(cosEmitter, 0.0);
        if (cosGeometry <= 1.0e-5) continue;
        float cosKernel = twoSided ? max(cosGeometry, 0.25) : cosGeometry;

        // The emission of the drawn texel decides, exactly as for the surface NEE (blockEmitterCandidate): the blue channel of
        // the material texel times the record's strength, the fixed red of a lit redstone texel, and for the quads of
        // captured entities (their texels live in a buffer this pass does not bind) the record's strength and a warm tint.
        float3 hue = float3(1.0, 0.82, 0.60);
        float emission = max(emitter.edge1.w, 0.0);
        if (uint(emitter.uvCorner.w + 0.5) == 0u) {
            float2 uv = emitter.uvCorner.xy + emitter.uvEdges.xy * quad.x + emitter.uvEdges.zw * quad.y;
            float4 materialTexel = materialAtlas.SampleLevel(uv, 0.0);
            bool redstone = emitter.edge2.w > 1.5 && ((255u - uint(materialTexel.a * 255.0 + 0.5)) & 4u) != 0u;
            if (redstone) {
                hue = float3(1.0, 0.04, 0.02);
                emission = 1.0;   // radiance / texelEmission * phat cancels the texel strength for a redstone texel
            } else {
                hue = emitterAttenuationColor(albedoAtlas.SampleLevel(uv, 0.0).rgb);
                emission = materialTexel.b * emission;
            }
        }
        if (emission <= 0.0) continue;
        // The same estimate the surface NEE folds into its target density, times the quad area over the draw probability.
        float3 energy = hue * (emission * EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN)
            * (cosKernel * emitterWindow(d2, EMITTER_RADIUS) * emitterAttenuation(d2)
               * max(emitter.corner.w, 1.0e-6) / max(chosen.probability, 1.0e-6));
        float peak = max(energy.r, max(energy.g, energy.b));
        if (peak > EMITTER_GLOW_MAX_ENERGY) energy *= EMITTER_GLOW_MAX_ENERGY / peak;

        Ray shadowRay = makeRay(p, L, rayBias, max(distanceToLight - 0.05, 0.01));
        if (!traversal.traceVisibility(shadowRay).visible) continue;
        accumulation += energy * (sigma * EMITTER_GLOW_PHASE * transmittance * dt);
    }
    float3 glow = accumulation * gain;
    return isFinite(glow) ? glow : float3(0.0);
}

// ---- Temporal reprojection --------------------------------------------------

// Reprojects the end point of a marched ray into the previous frame and
// returns the half-resolution history pixel (texel centres at integers).
// `worldDelta` translates the current camera-relative point into the previous
// frame's camera-relative space (current raw camera origin minus previous;
// the host computes it).  `flipY` mirrors NDC.y exactly like the legacy pass.
// `ok` is false when the point falls behind the near plane.
float2 shaftHistoryPixel(float4x4 prevViewProjection, float3 worldDelta,
        float3 rayEnd, float2 fullSize, bool flipY, out bool ok) {
    ok = false;
    float4 clip = mul(prevViewProjection, float4(rayEnd + worldDelta, 1.0));
    if (clip.w <= 1.0e-4) return float2(0.0);
    float2 ndc = clip.xy / clip.w;
    if (flipY) ndc.y = -ndc.y;
    float2 pixel = (ndc * 0.5 + 0.5) * fullSize * 0.5 - 0.5;
    ok = true;
    return pixel;
}

// Range tolerance of a history tap: a shaft whose length changed more than
// this is disoccluded (legacy: 0.08 * tEnd + 0.4).
float shaftHistoryTolerance(float tEnd) {
    return 0.08 * tEnd + 0.4;
}

// ---- Display composite ------------------------------------------------------

// Adds the upsampled scattering to the tone-mapped display pixel in scene
// space: inverse tone map, add the exposure-scaled scattering, tone map back,
// and return only the DIFFERENCE against the stored colour.  A pixel without
// scattering is therefore bit identical and the highlights are never
// re-quantised.  `tonemapped` must be false for a linear display target, where
// the scattering is added directly.
float3 shaftDisplayComposite(float3 displayRgb, float3 scatter, float exposure,
        bool tonemapped, uint toneMode) {
    float3 delta = scatter;
    if (tonemapped && toneMode == TM_MODE_PEAK) {
        float3 linear = tonemapInverse(displayRgb);
        delta = tonemapColor(linear + scatter * exposure) - tonemapColor(linear);
    } else if (tonemapped) {
        // This pass has no scene-HDR base. Add bounded display-linear scattering
        // rather than applying the unrelated PEAK inverse to a standard curve.
        float3 added = max(scatter * exposure, float3(0.0));
        if (all(added == float3(0.0))) return float3(0.0);
        float3 base = tmDecodeSrgb(displayRgb);
        delta = tmEncodeSrgb(1.0 - (1.0 - base) * exp(-added)) - displayRgb;
    } else {
        delta = scatter * exposure;
    }
    return delta;
}
