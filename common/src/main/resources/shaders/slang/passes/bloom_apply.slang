// Bloom, stage 3: apply.
//
// image = image + (tonemapColor(lin + bloom + glare) - tonemapColor(lin)): the glow is added BEFORE the shoulder of
// the tone map, so a glowing lamp saturates towards white only as far as the curve allows and the colours of the
// emission survive. A pixel without glow is bit identical. Sun glare (halo, plus the atmospheric bleed on
// foreground geometry) is added in the same dispatch.

import "bloom/bloom.slang";
import tonemap_curve;

[[vk::binding(0, 0)]] Sampler2D srcTex;
// The format must match the view the host binds (RtBloomPass: RGBA8 display image). An explicit format also keeps the
// module off the optional shaderStorageImageRead/WriteWithoutFormat features.
[[vk::image_format("rgba8")]] [[vk::binding(2, 0)]] RWTexture2D<float4> finalImg;
[[vk::binding(3, 0)]] Sampler2D depthTex;
[[vk::binding(4, 0)]] Sampler2D hdrSourceTex;

[[vk::push_constant]] BloomPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 size;
    finalImg.GetDimensions(size.x, size.y);
    int2 p = int2(tid.xy);
    if (p.x >= int(size.x) || p.y >= int(size.y)) {
        return;
    }
    float2 uv = (float2(p) + 0.5) / float2(size);
    float3 add = pc.p0.z > 0.0 ? srcTex.SampleLevel(uv, 0.0).rgb * pc.p0.z : float3(0.0);

    float glareK = pc.sunDir.w;
    if (glareK > 0.0) {
        float3 d = bloomPixelDir(p, int2(size), pc);
        float cosA = dot(d, normalize(pc.sunDir.xyz));
        if (cosA > 0.0) {
            float theta = acos(clamp(cosA, -1.0, 1.0));
            bool pixelIsSky = bloomIsSky(depthTex, p);
            // On foreground geometry (trees/blocks) only a soft tight atmospheric bleed near the sun core, never
            // long star spikes.
            float fgMask = pixelIsSky ? 1.0 : (0.18 * exp(-theta / 0.045));
            // Halo: compact Lorentzian core + gentle exponential glow.
            float g = (0.38 / (1.0 + (theta / 0.028) * (theta / 0.028)) + 0.045 * exp(-theta / 0.18)) * fgMask;
            if (g > 1.0e-3) {
                float vis = srcTex.Load(int3(0, 0, 0)).a;
                add += float3(1.0, 0.93, 0.80) * (g * 2.2 * glareK * vis);
            }
        }
    }

    add = bloomSanitize(add);
    if (bloomPeak(add) <= 1.0e-4) {
        return;
    }
    float4 c = finalImg[p];
    float3 delta;
    if (bloomSourceMode(pc) == 2u) {
        float3 base = bloomSanitize(hdrSourceTex.Load(int3(p, 0)).rgb) * max(pc.p0.w, 0.0);
        uint mode = bloomToneMode(pc);
        delta = tonemapColorMode(base + add, mode) - tonemapColorMode(base, mode);
    } else if (bloomSourceMode(pc) != 0u && bloomToneMode(pc) == TM_MODE_PEAK) {
        float3 lin = tonemapInverse(c.rgb);
        delta = tonemapColor(lin + add) - tonemapColor(lin);
    } else if (bloomSourceMode(pc) != 0u) {
        // A denoiser without an HDR export keeps a bounded display-linear approximation.
        float3 base = tmDecodeSrgb(c.rgb);
        delta = tmEncodeSrgb(1.0 - (1.0 - base) * exp(-add)) - c.rgb;
    } else {
        delta = add;
    }
    finalImg[p] = float4(clamp(c.rgb + delta, float3(0.0), float3(1.0)), c.a);
}
