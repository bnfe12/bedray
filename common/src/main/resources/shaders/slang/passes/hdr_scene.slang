// HDR output, stage 1 (runs in Bedray's own frame command buffer, right before the presented image is copied to the engine target).
//
// Builds the HDR version of the world picture from what the frame already has:
//   displayColor  the finished SDR picture (tone mapped, with bloom and glare), sRGB encoded
//   hdrSource     the exposed-free linear HDR radiance the denoiser (or the raw export) resolved
// and writes
//   hdrScene      linear Rec.709 light, 1.0 = paper white (the SDR white), highlights above it
//   sdrReference  a copy of displayColor; stage 2 compares the final frame with it to find the pixels the GUI touched
//
// Only the highlights change: below `kneeLow` (exposed luminance) the SDR picture is kept exactly, so the SDR look,
// grade and vibrance survive. Above it the colour of the SDR pixel is scaled towards a soft shoulder that ends at the
// display peak (peakRatio = peak nits / paper white nits), so a lamp or the sun reaches real HDR brightness while the
// hue stays the one the player tuned.

[[vk::image_format("rgba8")]] [[vk::binding(0, 0)]] RWTexture2D<float4> displayColor;
[[vk::image_format("rgba16f")]] [[vk::binding(1, 0)]] RWTexture2D<float4> hdrSource;
[[vk::image_format("rgba16f")]] [[vk::binding(2, 0)]] RWTexture2D<float4> hdrScene;
[[vk::image_format("rgba8")]] [[vk::binding(3, 0)]] RWTexture2D<float4> sdrReference;

struct HdrScenePush {
    uint2 size;
    float exposure;     // factor the compose pass applies to hdrSource
    float peakRatio;    // peak nits / paper white nits, >= 1
    float kneeLow;      // exposed luminance where the expansion starts
    float kneeHigh;     // exposed luminance where it is complete
    float pad0;
    float pad1;
};

[[vk::push_constant]] HdrScenePush pc;

static const float3 LUMA709 = float3(0.2126, 0.7152, 0.0722);

float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4));
    return select(c <= float3(0.04045), lo, hi);
}

// Identity up to 1.0 (paper white), then an exponential shoulder whose asymptote is the peak ratio.
float hdrShoulder(float x, float peak) {
    if (x <= 1.0 || peak <= 1.0) return x;
    float room = peak - 1.0;
    return 1.0 + room * (1.0 - exp(-(x - 1.0) / room));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint2 p = tid.xy;
    if (any(p >= pc.size)) return;
    float4 sdr = displayColor[p];
    sdrReference[p] = sdr;
    float3 lin = srgbToLinear(saturate(sdr.rgb));
    float3 scene = max(hdrSource[p].rgb, float3(0.0)) * max(pc.exposure, 0.0);
    if (any(isnan(scene)) || any(isinf(scene))) scene = float3(0.0);
    float lumSdr = max(dot(lin, LUMA709), 1.0e-3);
    float lumScene = dot(scene, LUMA709);
    float blend = smoothstep(pc.kneeLow, max(pc.kneeHigh, pc.kneeLow + 1.0e-3), lumScene);
    float target = hdrShoulder(lumScene, pc.peakRatio);
    float gain = lerp(1.0, max(1.0, target / lumSdr), blend);
    float3 hdr = lin * gain;
    // The brightest channel never passes the peak, whatever the hue.
    float top = max(hdr.r, max(hdr.g, hdr.b));
    if (top > pc.peakRatio) hdr *= pc.peakRatio / top;
    hdrScene[p] = float4(hdr, 1.0);
}
