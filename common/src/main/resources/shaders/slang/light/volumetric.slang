// Volumetric light shafts ("god rays") and air fog math for Bedray 2.0.
//
// Port of the legacy GLSL `rt_volumetric.comp` + the fog helpers of
// `rt_common_light.glsl`, rewritten against the Bedray 2.0 contracts: the
// march takes an `ISceneTracer` for the shaft's shadow rays and the host passes
// every input explicitly (there are no bindings and no global state here).
// The two compute entries that drive it live in
// `passes/volumetric_march.slang` (half resolution march + temporal store) and
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
    uint n = clamp(steps, 2u, 48u);
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
        bool tonemapped) {
    float3 delta = scatter;
    if (tonemapped) {
        float3 linear = tonemapInverse(displayRgb);
        delta = tonemapColor(linear + scatter * exposure) - tonemapColor(linear);
    } else {
        delta = scatter * exposure;
    }
    return delta;
}
