// Compile-only harness for the typed water surface model.
//
// surface/water.slang is imported by surface.slang, so the path-trace root
// already instantiates it; this harness compiles the module on its own and
// reads every public entry with the argument shapes the decoder uses.  If a
// signature drifts (for example the slope filter's out-parameter or the
// classification predicate), this fails before the whole renderer does.
//
// It also pins the parts the decoder relies on but cannot see: a wave normal
// never crosses to the far side of its geometric normal, and the two slope
// overloads agree when the footprint is zero.
//
// The second half reads the medium: the extinction of a darker tint dominates a
// whiter one (the biome dye), a non-scattering or outside segment adds nothing,
// an inside segment adds a finite positive colour, transmittance falls with
// length but stays positive, and the enter/leave rules only fire on horizontal
// faces in the direction that crosses them.
//
// The last half reads the beam a light sends into the body: it arrives travelling
// downward, its column is bounded by the sample's distance, its crossing loss
// stays inside the unit interval, and its focus stays between the bounds the
// module declares.
//
// Usage (same arguments as gradle/slang.gradle):
//   slangc -target spirv -profile cs_6_5 -entry main ^
//     -matrix-layout-column-major -I common/src/main/resources/shaders/slang ^
//     -o <temp>/verify_water.spv ^
//     common/src/test/resources/shaders/slang/verify_water.slang
//
// This proves generic code generation only.  It does not dispatch, and it
// does not prove a visual result.

import "surface/water.slang";

[[vk::binding(0, 0)]] RWStructuredBuffer<float4> sink;

[shader("compute")]
[numthreads(1, 1, 1)]
void main() {
    float3 up = float3(0.0, 1.0, 0.0);
    float3 down = float3(0.0, -1.0, 0.0);
    float3 side = float3(0.6, 0.0, -0.8);

    float lostFiltered;
    float2 slopeFiltered = waterSlope(float2(3.5, -7.25), 12.0, 0.4, lostFiltered);
    float lostExplicit;
    float2 slopeExplicit = waterSlope(float2(3.5, -7.25), 12.0, 0.0, lostExplicit);
    float2 slopeFull = waterSlope(float2(3.5, -7.25), 12.0);

    float lostTop;
    float3 top = waterWaveNormal(float3(3.5, 64.0, -7.25), up, 12.0, 0.05, lostTop);
    float lostBottom;
    float3 bottom = waterWaveNormal(float3(0.0, 0.0, 0.0), down, 3.0, 1.0, lostBottom);
    float lostSide;
    float3 vertical = waterWaveNormal(float3(0.0, 0.0, 0.0), side, 3.0, 1.0, lostSide);

    float3 tint = waterBiomeTint(float3(0.40, 0.55, 0.90), up);
    float shadeTop = cardinalFaceShade(up);
    float shadeSide = cardinalFaceShade(side);

    bool isFluid = waterIsSurface(true, float3(0.02), 0.02, 0.0);
    bool isGlass = waterIsSurface(true, float3(0.04), 0.02, 0.0);
    bool isRough = waterIsSurface(true, float3(0.02), 0.05, 0.0);
    bool isOpaque = waterIsSurface(false, float3(0.02), 0.02, 0.0);

    bool sameSideTop = dot(top, up) > 0.0;
    bool sameSideBottom = dot(bottom, down) > 0.0;
    bool flatSide = dot(vertical, side) > 0.999;
    bool slopeAgree = length(slopeExplicit - slopeFull) <= 1.0e-6 * max(1.0, length(slopeFull));

    // ---- medium ----
    float3 clearSigma = waterSigmaT(float3(0.95));
    float3 swampSigma = waterSigmaT(float3(0.30, 0.45, 0.20));
    bool dyeDominates = swampSigma.r > clearSigma.r && swampSigma.r > swampSigma.g;
    bool scatterBelowOne = all(waterMediumFrom(float3(1.0), 64.0).scatterAlbedo < float3(1.0));

    WaterMedium outside = noWaterMedium();
    WaterMedium inside = waterMediumFrom(tint, 62.0);
    bool outsideIsClear = !outside.inside &&
        dot(waterSegmentTransmittance(outside, 3.0), float3(1.0)) > 2.999;
    bool outsideScattersNothing = dot(waterInScatter(outside, 60.0, float3(1.0), 3.0), float3(1.0)) == 0.0;

    float3 nearT = waterSegmentTransmittance(inside, 1.0);
    float3 farT = waterSegmentTransmittance(inside, 8.0);
    bool absorptionFalls = farT.r < nearT.r && farT.r > 0.0 && farT.b > 0.0;
    float3 scatter = waterInScatter(inside, 61.0, float3(1.0), 2.0);
    bool scatterPositive = all(scatter >= float3(0.0)) && max(scatter.r, max(scatter.g, scatter.b)) > 0.0;

    bool enters = waterEntersMedium(false, 1.0, -1.0) && !waterEntersMedium(true, 1.0, -1.0)
        && !waterEntersMedium(false, 0.0, -1.0) && !waterEntersMedium(false, 1.0, 1.0);
    bool leaves = waterLeavesMedium(true, 1.0, 1.0) && !waterLeavesMedium(false, 1.0, 1.0)
        && !waterLeavesMedium(true, 0.0, 1.0) && !waterLeavesMedium(true, 1.0, -1.0);

    // ---- beam ----
    float3 sun = normalize(float3(0.3, 1.0, -0.2));
    float3 receiver = float3(2.0, 58.0, 3.0);
    WaterBeam beam = waterBeamToLight(sun, 40.0, receiver, inside);
    bool beamArrivesFromAbove = beam.valid && beam.direction.y < 0.0 && beam.column > 0.0
        && beam.solidAngle > 0.0 && beam.column < 40.0;
    bool flatBeam = length(waterBeamTravel(sun, float3(0.0, 1.0, 0.0)) - beam.direction) < 1.0e-4;
    bool shallowReceiverHasNoBeam = !waterBeamToLight(sun, 40.0, float3(2.0, 62.5, 3.0), inside).valid
        && !waterBeamToLight(sun, 40.0, receiver, outside).valid
        && !waterBeamToLight(-sun, 40.0, receiver, inside).valid;
    float3 crossing = waterBeamTransmittance(sun.y);
    bool crossingIsLoss = all(crossing >= float3(0.0)) && all(crossing <= float3(1.0));
    float2 footprint = waterBeamAtDepth(beam.entry.xz, beam.entry.y, receiver.y, sun, 12.5, 1.0, 1.0);
    bool footprintIsFinite = abs(footprint.x) < 1.0e6 && abs(footprint.y) < 1.0e6;
    float focus = waterBeamFocus(beam, receiver, sun, 12.5, 1.0);
    bool focusIsBounded = focus >= WATER_CAUSTIC_MIN && focus <= WATER_CAUSTIC_MAX;
    bool noBeamFocusIsNeutral = waterBeamFocus(invalidWaterBeam(), receiver, sun, 12.5, 1.0) == 1.0;
    // The style switch owns the wave field and nothing else: a full mix is the
    // untouched face, no mix is the flat geometric normal, and the beam still
    // bends through whatever face the style left it with.
    float lostStyling;
    float lostDampedStyling;
    float3 styledFull = waterStyledWaveNormal(float3(3.5, 64.0, -7.25), up, 12.0, 0.05, 1.0, lostStyling);
    float3 styledFlat = waterStyledWaveNormal(float3(3.5, 64.0, -7.25), up, 12.0, 0.05, 0.0, lostDampedStyling);
    bool mixEndsAreTheField = length(styledFull - top) < 1.0e-5 && length(styledFlat - up) < 1.0e-5
        && lostStyling == lostTop && lostDampedStyling == lostTop;
    float2 dampedFootprint = waterBeamAtDepth(beam.entry.xz, beam.entry.y, receiver.y, sun, 12.5, 1.0, 0.0);
    bool styleReachesTheBeam = length(dampedFootprint - footprint) > 1.0e-4
        && abs(dampedFootprint.x) < 1.0e6 && abs(dampedFootprint.y) < 1.0e6
        && waterBeamFocus(beam, receiver, sun, 12.5, 0.0) >= WATER_CAUSTIC_MIN;

    sink[0] = float4(slopeFiltered.x, slopeFiltered.y, lostFiltered, lostTop);
    sink[1] = float4(top.x, top.y, top.z, lostBottom + lostSide);
    sink[2] = float4(bottom.x, bottom.y, bottom.z, vertical.x);
    sink[3] = float4(tint.x, shadeTop, shadeSide, (isFluid ? 1.0 : 0.0) +
        (isGlass ? 2.0 : 0.0) + (isRough ? 4.0 : 0.0) + (isOpaque ? 8.0 : 0.0));
    sink[4] = float4(sameSideTop ? 1.0 : 0.0, sameSideBottom ? 1.0 : 0.0,
        flatSide ? 1.0 : 0.0, slopeAgree ? 1.0 : 0.0);
    sink[5] = float4(clearSigma.x, swampSigma.x, swampSigma.g, swampSigma.b);
    sink[6] = float4(dyeDominates ? 1.0 : 0.0, scatterBelowOne ? 1.0 : 0.0,
        outsideIsClear ? 1.0 : 0.0, outsideScattersNothing ? 1.0 : 0.0);
    sink[7] = float4(absorptionFalls ? 1.0 : 0.0, scatterPositive ? 1.0 : 0.0,
        enters ? 1.0 : 0.0, leaves ? 1.0 : 0.0);
    sink[8] = float4(beamArrivesFromAbove ? 1.0 : 0.0, flatBeam ? 1.0 : 0.0,
        shallowReceiverHasNoBeam ? 1.0 : 0.0, crossingIsLoss ? 1.0 : 0.0);
    sink[9] = float4(footprintIsFinite ? 1.0 : 0.0, focusIsBounded ? 1.0 : 0.0,
        noBeamFocusIsNeutral ? 1.0 : 0.0, beam.column);
    sink[10] = float4(mixEndsAreTheField ? 1.0 : 0.0, styleReachesTheBeam ? 1.0 : 0.0,
        waterBeamFocus(beam, receiver, sun, 12.5, WATER_WAVE_MIX_VANILLA), dampedFootprint.x);
}
