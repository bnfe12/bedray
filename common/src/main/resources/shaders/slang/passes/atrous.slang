// À-trous denoiser, one wavelet level: src -> dst with taps spaced `stepSize` pixels apart.
// srcImg: rgb radiance, a temporal age (passed through). normalDepthImg: xyz unit world normal, w linear depth
// (clip.w), 0 = sky. This is an edge-avoiding filter, not SVGF: no variance or moments are tracked.

import "denoise/atrous.slang";

[[vk::binding(0, 0)]] Texture2D<float4> srcImg;        // rgb: radiance
[[vk::binding(1, 0)]] Texture2D<float4> normalDepthImg; // xyz: world normal, w: linear view depth (0 = sky)
[[vk::image_format("rgba16f")]] [[vk::binding(2, 0)]] RWTexture2D<float4> dstImg;

[[vk::push_constant]] ATrousPush pc;

ATrousTap loadTap(int2 p, int2 size) {
    ATrousTap tap;
    tap.radiance = float3(0.0);
    tap.normal = float3(0.0, 0.0, 1.0);
    tap.depth = 0.0;
    tap.valid = false;
    if (p.x < 0 || p.y < 0 || p.x >= size.x || p.y >= size.y) {
        return tap;
    }
    float4 nd = normalDepthImg.Load(int3(p, 0));
    if (isnan(nd.w) || isinf(nd.w) || !(nd.w > 0.0) || !atrousUnitNormal(nd.xyz, tap.normal)) {
        return tap;                      // sky, non-finite depth or unusable normal: not a surface
    }
    tap.depth = nd.w;
    tap.radiance = atrousSanitize(srcImg.Load(int3(p, 0)).rgb);
    tap.valid = true;
    return tap;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 usize;
    dstImg.GetDimensions(usize.x, usize.y);
    int2 size = int2(usize);
    int2 p = int2(tid.xy);
    if (p.x >= size.x || p.y >= size.y) {
        return;
    }

    float srcAge = atrousSanitizeAge(srcImg.Load(int3(p, 0)).a);
    if (!atrousPushValid(pc)) {
        dstImg[p] = float4(atrousSanitize(srcImg.Load(int3(p, 0)).rgb), srcAge);
        return;
    }

    ATrousTap center = loadTap(p, size);
    if (!center.valid) {
        // Sky and missing geometry pass through untouched.
        dstImg[p] = float4(atrousSanitize(srcImg.Load(int3(p, 0)).rgb), srcAge);
        return;
    }

    // 3x3 neighbourhood statistics for the adaptive colour term.
    float sumLuma = 0.0;
    float sumLumaSq = 0.0;
    float count = 0.0;
    for (int dy = -1; dy <= 1; ++dy) {
        for (int dx = -1; dx <= 1; ++dx) {
            ATrousTap n = loadTap(p + int2(dx, dy), size);
            if (!n.valid) {
                continue;
            }
            float l = atrousLuma(n.radiance);
            sumLuma += l;
            sumLumaSq += l * l;
            count += 1.0;
        }
    }
    float invCount = 1.0 / max(count, 1.0);
    float lumaScale = atrousLocalDeviation(atrousLuma(center.radiance), sumLuma * invCount, sumLumaSq * invCount);

    float3 sum = float3(0.0);
    float weightSum = 0.0;
    for (int dy = -2; dy <= 2; ++dy) {
        for (int dx = -2; dx <= 2; ++dx) {
            ATrousTap tap = loadTap(p + int2(dx, dy) * pc.stepSize, size);
            float kernelWeight = ATROUS_KERNEL[abs(dx)] * ATROUS_KERNEL[abs(dy)];
            // The centre tap is always accepted with its full kernel weight, so the sum cannot vanish.
            float w = (dx == 0 && dy == 0) ? kernelWeight : atrousWeight(center, tap, kernelWeight, lumaScale, pc);
            sum += tap.radiance * w;
            weightSum += w;
        }
    }
    // Alpha carries the temporal age and must survive every wavelet level untouched.
    dstImg[p] = float4(sum / max(weightSum, 1.0e-6), srcAge);
}
