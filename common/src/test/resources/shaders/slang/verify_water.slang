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

    sink[0] = float4(slopeFiltered.x, slopeFiltered.y, lostFiltered, lostTop);
    sink[1] = float4(top.x, top.y, top.z, lostBottom + lostSide);
    sink[2] = float4(bottom.x, bottom.y, bottom.z, vertical.x);
    sink[3] = float4(tint.x, shadeTop, shadeSide, (isFluid ? 1.0 : 0.0) +
        (isGlass ? 2.0 : 0.0) + (isRough ? 4.0 : 0.0) + (isOpaque ? 8.0 : 0.0));
    sink[4] = float4(sameSideTop ? 1.0 : 0.0, sameSideBottom ? 1.0 : 0.0,
        flatSide ? 1.0 : 0.0, slopeAgree ? 1.0 : 0.0);
}
