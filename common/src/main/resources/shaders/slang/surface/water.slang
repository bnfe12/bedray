// Water surface model for the typed surface decoder.
//
// This is a port of the legacy GLSL wave field (rt_caustics.glsl) and the
// classification / tint rules of rt_common_water.glsl.  It owns three things
// and nothing else:
//
//   * the analytic wave field: one slope function in world X/Z that is the only
//     source of the water shading normal, filtered by the pixel footprint so
//     far water fades to a soft sheen instead of aliasing;
//   * the water test: which translucent surface is a fluid rather than glass;
//   * the biome tint: the vertex colour with the baked vanilla face shade
//     divided out.
//
// The volumetric medium (depth absorption and in-scattering) is here too:
// `waterSigmaT` turns a biome tint into an extinction coefficient, and
// `waterSegmentTransmittance` / `waterInScatter` evaluate one segment inside
// the body.  The path tracer and the visibility walk own the state (when a ray
// is inside a body and which body); this module only does the arithmetic.
//
// The beam a light sends into the body is here as well (`waterBeamToLight`): a
// plane interface maps one air direction to one water direction, so the path
// the sun took to an underwater receiver is knowable without tracing it.  The
// wave field then focuses that beam, and `waterBeamFocus` measures the focus as
// the area the bundle spreads over on the floor - the caustic.

public module water;

import "contracts/math.slang";

// Index of refraction of clear water.  Ice and glass keep the option default.
static const float WATER_IOR = 1.333;

// Radians per pixel of the 3D view (about 70 deg fov at 750 px, conservative),
// used to turn the hit distance into a surface footprint for the wave filter.
static const float WATER_PIXEL_ANGLE = 0.0016;

// A calm lake reads as flat glass from a player's viewing angle, so the long
// swells carry most of the slope and the short ripples are small enough not to
// shimmer.  Directions are unit XZ, k is rad/block and w is the peak slope.
static const int WATER_WAVES = 6;
static const float4 WATER_WAVE_A[WATER_WAVES] = {
    float4(0.800, 0.600, 1.00, 0.050),
    float4(-0.700, 0.714, 1.70, 0.048),
    float4(0.312, -0.950, 2.90, 0.036),
    float4(-0.911, -0.412, 4.60, 0.030),
    float4(0.550, 0.835, 7.30, 0.018),
    float4(-0.200, -0.980, 11.5, 0.012)
};
static const float WATER_WAVE_PHASE[WATER_WAVES] = { 0.0, 1.3, 2.9, 4.1, 5.2, 0.7 };

// How much of the wave tilt the vanilla style keeps.  The number is the legacy
// sheet's own mix (rt_common_surface.glsl): clearly visible ripples, not a storm.
static const float WATER_WAVE_MIX_VANILLA = 0.55;

// Gradient of the height field (dh/dx, dh/dz) at world xz.  A wave whose period
// the footprint does not resolve fades out, and the slope variance it leaves
// behind is returned as the extra roughness of the sheen.
float2 waterSlope(float2 xz, float time, float footprint, out float lostVar) {
    float2 slope = float2(0.0);
    lostVar = 0.0;
    for (int i = 0; i < WATER_WAVES; ++i) {
        float4 w = WATER_WAVE_A[i];
        float k = w.z;
        float omega = 0.9 * sqrt(k);
        float attenuation = 1.0 - smoothstep(0.25, 1.0, k * footprint);
        float phase = k * dot(w.xy, xz) - omega * time + WATER_WAVE_PHASE[i];
        slope += w.xy * (w.w * attenuation * cos(phase));
        lostVar += 0.5 * w.w * w.w * (1.0 - attenuation * attenuation);
    }
    return slope;
}

float2 waterSlope(float2 xz, float time) {
    float lostVar;
    return waterSlope(xz, time, 0.0, lostVar);
}

// Shading normal of a horizontal water face, perturbed by the wave field and
// kept on the same side as the geometric normal.  Flowing water (a sloped top
// quad) carries the wave pattern downhill; its vertical sides stay flat.
float3 waterWaveNormal(float3 worldPosition, float3 geometricNormal, float time,
        float footprint, out float lostVar) {
    lostVar = 0.0;
    if (abs(geometricNormal.y) < 0.7) {
        return geometricNormal;
    }
    float2 xz = worldPosition.xz;
    float2 downhill = geometricNormal.xz * sign(geometricNormal.y);
    float flow = length(downhill);
    if (flow > 0.02) {
        xz -= downhill / flow * (time * min(flow * 10.0, 1.6));
    }
    float2 slope = waterSlope(xz, time, footprint, lostVar);
    // The underside of the sheet sees the same slope with the opposite sign.
    float3 perturbed = safeNormalize(geometricNormal +
        float3(-slope.x, 0.0, -slope.y) * sign(geometricNormal.y), geometricNormal);
    return dot(perturbed, geometricNormal) > 0.0 ? perturbed : geometricNormal;
}

// The same face under the player's style switch.  Both styles tilt the face by
// the same analytic field; the mix only decides how far it leans back toward the
// flat geometric normal, so the wave shape is a slider and never a different
// surface the light has to be re-solved for.
float3 waterStyledWaveNormal(float3 worldPosition, float3 geometricNormal, float time,
        float footprint, float waveMix, out float lostVar) {
    float3 wave = waterWaveNormal(worldPosition, geometricNormal, time, footprint, lostVar);
    if (waveMix >= 1.0) {
        return wave;
    }
    return safeNormalize(lerp(geometricNormal, wave, max(waveMix, 0.0)), geometricNormal);
}

// Vanilla face shade baked into a section vertex colour (mirror of
// RtLighting.cardinalShade): the biome tint of a fluid is that colour with the
// shade divided back out.
float cardinalFaceShade(float3 normal) {
    return normal.y * normal.y * (normal.y > 0.0 ? 1.0 : 0.5)
        + normal.x * normal.x * 0.6 + normal.z * normal.z * 0.8;
}

float3 waterBiomeTint(float3 vertexColor, float3 geometricNormal) {
    return clamp(vertexColor / max(cardinalFaceShade(geometricNormal), 0.5),
        float3(0.0), float3(1.0));
}

// A fluid is the one translucent surface the auto-PBR atlas tags with water's
// reflectance and roughness (f0 0.02, roughness 0.02).  Glass is f0 0.04 and
// ice is roughness 0.05, so both stay panes.  The texel alpha is deliberately
// not part of the test: the 26.3 water sprite is opaque and its translucency
// lives in the render pass.
bool waterIsSurface(bool translucent, float3 f0, float roughness, float metallic) {
    return translucent && luminance(f0) <= 0.025 && roughness <= 0.035 && metallic < 0.5;
}

// ---- volumetric medium -----------------------------------------------------
//
// Ported from rt_common_water.glsl.  Clear water absorbs red first and blue
// last, a biome tint adds a dye on top (swamp green, warm ocean turquoise), and
// a weak grey scattering term gives deep water a body: with absorption alone
// there is nothing left to see once the bottom is attenuated away, which is
// what made deep oceans black in an earlier revision.

static const float3 WATER_CLEAR_ABSORPTION = float3(0.17, 0.036, 0.013); // 1/block
static const float WATER_SCATTERING = 0.055;                             // 1/block, nearly grey
static const float WATER_MULTIPLE_SCATTER_FLOOR = 0.65;                  // share of the sky that reaches any depth

// Extinction of the body for a biome tint (the vertex colour with the baked
// face shade undone).  The dye term reuses the same tint the surface filter
// used, so a swamp and an ocean differ in colour and in how fast they darken.
float3 waterSigmaT(float3 tintIn) {
    float3 tint = clamp(tintIn, float3(0.02), float3(1.0));
    float3 dye = clamp(0.05 * (-log(tint)), float3(0.0), float3(0.20));
    return WATER_CLEAR_ABSORPTION + dye + float3(WATER_SCATTERING);
}

// One segment inside a water body.  `surfY` is the camera-relative height of
// the surface the ray entered through; the in-scatter uses the depth at the
// segment's midpoint so a column darkens with depth instead of one flat tint.
struct WaterMedium {
    bool   inside;
    float3 sigmaT;
    float3 scatterAlbedo;
    float  surfY;
};

WaterMedium noWaterMedium() {
    WaterMedium medium;
    medium.inside = false;
    medium.sigmaT = float3(0.0);
    medium.scatterAlbedo = float3(0.0);
    medium.surfY = 0.0;
    return medium;
}

WaterMedium waterMediumFrom(float3 tintIn, float surfY) {
    WaterMedium medium;
    medium.inside = true;
    medium.sigmaT = waterSigmaT(tintIn);
    medium.scatterAlbedo = float3(WATER_SCATTERING) / medium.sigmaT;
    medium.surfY = surfY;
    return medium;
}

// A boundary toggles the medium only on horizontal faces (the sides of flowing
// water are thin sheets, not a surface the body can be entered through), and
// only in the direction the face allows: down enters, up leaves.
bool waterEntersMedium(bool inside, float faceNormalY, float directionY) {
    return abs(faceNormalY) > 0.5 && directionY < 0.0 && !inside;
}

bool waterLeavesMedium(bool inside, float faceNormalY, float directionY) {
    return abs(faceNormalY) > 0.5 && directionY > 0.0 && inside;
}

float3 waterSegmentTransmittance(WaterMedium medium, float length) {
    return medium.inside ? exp(-medium.sigmaT * max(length, 0.0)) : float3(1.0);
}

// Radiance the segment scatters towards the eye.  `downRadiance` is the light
// arriving at the surface from above (the environment dome), attenuated by the
// column above the segment; the multiple-scatter floor keeps deep water blue
// instead of fading to black.
float3 waterInScatter(WaterMedium medium, float segmentMidY, float3 downRadiance, float length) {
    if (!medium.inside) return float3(0.0);
    float3 transmittance = waterSegmentTransmittance(medium, length);
    float depth = max(medium.surfY - segmentMidY, 0.0);
    float3 column = WATER_MULTIPLE_SCATTER_FLOOR
        + (1.0 - WATER_MULTIPLE_SCATTER_FLOOR) * exp(-medium.sigmaT * depth * 0.65);
    return downRadiance * medium.scatterAlbedo * column * (float3(1.0) - transmittance);
}

// ---- the beam a light sends into the body ------------------------------------
//
// A horizontal fluid face is a plane, so one air-side direction maps to one
// water-side direction and the key light reaches an underwater receiver along a
// path that can be named without tracing it: Snell gives the direction, the
// surface height the medium already carries gives the crossing point, and the
// distance between them is the column the light had to cross.  A straight sight
// line to the disc is not that path - the whole sky compresses into the Snell
// window, so the sun is always high overhead under water.
//
// The wave field then decides how much of the beam lands on this particular
// square of floor.  A dimple gathers several surface squares onto one floor
// square; the ratio is the Jacobian of the map from entry point to footprint,
// and reading it costs three refractions through three wave normals and no trace.

// Reflectance of the air-water face at normal incidence.  The fluid decode hands
// the same number to the optical interface (surface.slang), so a beam that
// crosses the plane and a ray that hits the traced face pay the same crossing.
static const float WATER_INTERFACE_F0 = 0.02;

// The finite difference that reads the focus is a wave-scale question: the ripple
// that matters at depth is about as wide as the depth, and a step fixed in blocks
// would measure ripples that have already blurred into a sheen by the time the
// floor is three blocks down.
static const float WATER_CAUSTIC_STEP_MIN = 0.25;
static const float WATER_CAUSTIC_STEP_MAX = 4.0;

// A fold focuses the bundle onto a line and the integrand has no answer for that.
// The clamp bounds what one sample can put into a history that lives for
// thousands of frames while still letting the pattern reach three times the mean.
static const float WATER_CAUSTIC_MIN = 0.1;
static const float WATER_CAUSTIC_MAX = 3.0;

struct WaterBeam {
    bool   valid;
    float3 direction;   // unit: the direction light travels inside the body (points down)
    float  column;      // distance from the receiver to the surface along the beam
    float3 entry;       // where the beam crosses the surface plane
    float  solidAngle;  // solid-angle air / solid-angle water: the weight an air-side
                        // sample needs once the BSDF is evaluated on the water side
};

WaterBeam invalidWaterBeam() {
    WaterBeam beam;
    beam.valid = false;
    beam.direction = float3(0.0, 1.0, 0.0);
    beam.column = 0.0;
    beam.entry = float3(0.0);
    beam.solidAngle = 1.0;
    return beam;
}

// Snell at the fluid face for an air beam arriving from `lightDirection` (the
// receiver-to-light direction, so the beam itself runs the other way) through the
// face whose normal is `faceNormal`.  Air into water cannot totally internally
// reflect, so the only failure worth naming is a crest that has turned its face
// away from the beam; the flat sheet answers that one.
float3 waterBeamTravel(float3 lightDirection, float3 faceNormal) {
    float3 flat = refract(-lightDirection, float3(0.0, 1.0, 0.0), 1.0 / WATER_IOR);
    if (dot(faceNormal, lightDirection) <= 0.0) return flat;
    float3 bent = refract(-lightDirection, faceNormal, 1.0 / WATER_IOR);
    return dot(bent, bent) > 1.0e-8 ? safeNormalize(bent, flat) : flat;
}

// The beam the light takes into the body the receiver stands in.  Anything the
// plane model cannot answer - a light below the horizon, a receiver at or above
// the recorded surface, an emitter nearer than the column - leaves the beam
// invalid, and the caller keeps the straight sight line, which is the right path
// for a light that never crossed the face.
WaterBeam waterBeamToLight(float3 lightDirection, float distance, float3 position, WaterMedium medium) {
    WaterBeam beam = invalidWaterBeam();
    beam.direction = lightDirection;
    if (!medium.inside || !(distance > 0.0) || !(lightDirection.y > 0.0)) return beam;
    float depth = medium.surfY - position.y;
    if (!(depth > 0.0)) return beam;
    float3 travel = waterBeamTravel(lightDirection, float3(0.0, 1.0, 0.0));
    // The refracted beam is never shallower than the critical angle, so this
    // guard only fires on a degenerate sample; a horizontal beam would make the
    // column infinite.
    if (!(travel.y < -0.05)) return beam;
    float column = depth / -travel.y;
    if (!(column > 0.0) || !(column < distance)) return beam;
    beam.valid = true;
    beam.direction = travel;
    beam.column = column;
    beam.entry = position - travel * column;
    // Radiance rises by 1/eta-squared entering the denser side while the cone
    // narrows by eta-squared times the cosine ratio, and the two cancel in the
    // flux that crosses the same horizontal patch.  What is left for an
    // estimator that sampled air solid angles and now evaluates a water
    // direction is cos(air) / cos(water), so a floor under a flat sheet keeps
    // exactly the irradiance the surface lets through.
    beam.solidAngle = max(lightDirection.y, 1.0e-3) / max(-travel.y, 1.0e-3);
    return beam;
}

// Fresnel share of the flat face for a beam that crosses it at the air-side
// cosine `airCosine`.  The mirror of `surfaceInterfaceTransmittance`'s fluid
// branch in surface.slang: the colour of the water stays in the column, so the
// interface is charged for the reflection only.
float3 waterBeamTransmittance(float airCosine) {
    float cosine = clamp(airCosine, 0.05, 1.0);
    float fresnel = WATER_INTERFACE_F0 + (1.0 - WATER_INTERFACE_F0) * pow5(1.0 - cosine);
    return float3(1.0 - fresnel);
}

// Where the beam that enters at `entryXZ` stands horizontally once it has fallen
// to `receiverY`, measured through the wave normal of the face it entered.
float2 waterBeamAtDepth(float2 entryXZ, float entryY, float receiverY, float3 lightDirection,
        float time, float footprint, float waveMix) {
    float lostVar = 0.0;
    float3 face = waterStyledWaveNormal(float3(entryXZ.x, entryY, entryXZ.y),
        float3(0.0, 1.0, 0.0), time, footprint, waveMix, lostVar);
    float3 travel = waterBeamTravel(lightDirection, face);
    return entryXZ + travel.xz * ((entryY - receiverY) / max(-travel.y, 1.0e-3));
}

// How much of the beam lands on this receiver: the reciprocal of the area the
// bundle spreads over between the surface and the receiver's depth.  A flat sheet
// maps every square of surface onto a square of floor the same size and returns
// 1; a dimple that gathers three of them onto one returns 3.  Damping the waves
// damps the focus with them, because the bundle is bent by the same face the
// receiver's own shading normal uses.
float waterBeamFocus(WaterBeam beam, float3 position, float3 lightDirection, float time,
        float waveMix) {
    if (!beam.valid) return 1.0;
    float step = clamp(beam.column * 0.5, WATER_CAUSTIC_STEP_MIN, WATER_CAUSTIC_STEP_MAX);
    float2 base = beam.entry.xz;
    float2 centre = waterBeamAtDepth(base, beam.entry.y, position.y, lightDirection,
        time, step * 0.5, waveMix);
    float2 east = waterBeamAtDepth(base + float2(step, 0.0), beam.entry.y, position.y,
        lightDirection, time, step * 0.5, waveMix);
    float2 north = waterBeamAtDepth(base + float2(0.0, step), beam.entry.y, position.y,
        lightDirection, time, step * 0.5, waveMix);
    float2 dx = (east - centre) / step;
    float2 dz = (north - centre) / step;
    float det = dx.x * dz.y - dx.y * dz.x;
    // A non-positive determinant is a fold: the neighbouring beams have crossed
    // over each other, and the area they enclose is not the flux this receiver
    // gets.  Take the dim floor instead of the reciprocal of a signed number.
    if (!isFinite(det) || det <= 1.0e-3) return WATER_CAUSTIC_MIN;
    return clamp(1.0 / det, WATER_CAUSTIC_MIN, WATER_CAUSTIC_MAX);
}
