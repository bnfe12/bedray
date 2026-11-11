// Bedray 2.0 — bloom and sun glare (module).
//
// The legacy GLSL pass (shaders/glsl/rt_bloom.comp) selected one of four dispatches with `#define BLOOM_STAGE`. That
// preprocessor switch is gone: this module holds the behaviour and four thin entry points in passes/ own the
// bindings. Each entry point is a separate AOT program, so a change in one stage cannot change the others.
//
// Behaviour is unchanged, including the order of the operations and the tolerances:
//   prefilter_down  display image -> scene space -> soft peak threshold -> Karis 13 tap -> mip 0 (half res, RGBA16F)
//   down            mip i-1 -> i, Jimenez 2014 13 tap filter
//   up              mip i = mix(mip i, tent(mip i+1), scatter), in place
//   apply           image += tonemap(lin + bloom + glare) - tonemap(lin), glare in the same dispatch
//
// The sky and the clouds never glow (their bright edge against dark blocks used to be a halo around every
// silhouette); the sun has its own glare term.

public module bloom;

import tonemap_curve;

// Push constants: byte-for-byte the legacy 128-byte block of RtBloomPass. Field offsets are asserted by the Java
// caller through the generated manifest, not assumed here.
struct BloomPush {
    float4x4 invViewProj;   //   0 clip -> camera-relative world (no jitter)
    float4 sunDir;          //  64 xyz towards the key light, w glare strength (0 = off)
    float4 camOrigin;       //  80 xyz bob origin of the camera, w 1 = flip Y
    float4 p0;              //  96 x threshold (scene units), y knee, z bloom strength, w darkness 0..1 (1 - daylight)
    float4 p1;              // 112 x 1 = image is tone mapped, y scatter, zw sun position in NDC
};

static const float3 BLOOM_LUMA = float3(0.2126, 0.7152, 0.0722);
/** Ray distance the path tracer writes for a miss: anything at or beyond it is sky. */
static const float BLOOM_SKY_DISTANCE = 1.0e5;

float bloomPeak(float3 c) {
    return max(c.r, max(c.g, c.b));
}

float3 bloomToScene(float3 display, BloomPush pc) {
    return pc.p1.x > 0.5 ? tonemapInverse(display) : display;
}

// Soft threshold on the peak channel: 0 below threshold - knee, linear above threshold + knee, quadratic between.
float3 bloomPrefilter(float3 c, BloomPush pc) {
    float m = bloomPeak(c);
    float knee = max(pc.p0.y, 1.0e-4);
    float soft = clamp(m - pc.p0.x + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1.0e-5);
    float contrib = max(soft, m - pc.p0.x) / max(m, 1.0e-4);
    return c * max(contrib, 0.0);
}

float bloomKaris(float3 c) {
    return 1.0 / (1.0 + dot(c, BLOOM_LUMA));
}

float3 bloomSanitize(float3 c) {
    if (any(isnan(c)) || any(isinf(c))) {
        return float3(0.0);
    }
    return clamp(c, float3(0.0), float3(65000.0));
}

bool bloomIsSky(Sampler2D depth, int2 p) {
    return depth.Load(int3(p, 0)).r >= BLOOM_SKY_DISTANCE;
}

// The bloom reads the tonemapped RGBA8 image, and the inverse curve tops out at ~4.8 scene units: a torch flame that is
// hundreds of times brighter than the floor arrives as the same 1.0 as a lit white wall, so a scene-space threshold
// left nothing to glow (bloom on/off differed by < 0.1 of 255 at night, autotest rt_modes A/H, 2026-11-11). A pixel the
// tonemap pushed into its shoulder is therefore taken as clipped: its glow is the rebuilt colour times
// BLOOM_CLIP_GAIN, ramped in from the display value of the scene threshold up to white. Bedrock RTX torches carry a
// halo about one block wide; this is the LDR stand-in until the pass reads the HDR resolve.
// p0.w carries how dark the scene is (1 - daylight, RtAtmosphere): by day a clipped pixel may be a sunlit white wall,
// at night it is a light source, so the gain runs from 3 (day) to 6 (night). Only pixels at display 0.97+ ramp in: at night
// with a 0.9 threshold and gain 16 the torch-lit floor itself bloomed into a fog (autotest rt_modes A, 2026-11-11).
static const float BLOOM_CLIP_GAIN_DAY = 3.0;
static const float BLOOM_CLIP_GAIN_NIGHT = 6.0;

float3 bloomClippedSource(float3 display, BloomPush pc) {
    float md = bloomPeak(display);
    float t0 = bloomPeak(tonemapColor(float3(max(pc.p0.x, 0.05))));
    float w = smoothstep(clamp(t0, 0.97, 0.99), 0.995, md);
    if (w <= 0.0) {
        return float3(0.0);
    }
    return tonemapInverse(display) * (w * lerp(BLOOM_CLIP_GAIN_DAY, BLOOM_CLIP_GAIN_NIGHT, saturate(pc.p0.w)));
}

// One prefiltered tap of the first downsample. The sky contributes nothing, so its bright edge cannot glow.
float3 bloomFetchPre(Sampler2D src, Sampler2D depth, float2 uv, BloomPush pc) {
    uint2 size;
    depth.GetDimensions(size.x, size.y);
    int2 dp = clamp(int2(uv * float2(size)), int2(0), int2(size) - 1);
    if (bloomIsSky(depth, dp)) {
        return float3(0.0);
    }
    float3 display = src.SampleLevel(uv, 0.0).rgb;
    if (pc.p1.x > 0.5) {
        return bloomClippedSource(display, pc);
    }
    return bloomPrefilter(display, pc);
}

// 13 tap downsample, Jimenez 2014 ("Next Generation Post Processing in Call of Duty: Advanced Warfare").
float3 bloomDown13Sampled(Sampler2D src, float2 uv, float2 texel) {
    float3 a = src.SampleLevel(uv + float2(-2.0, -2.0) * texel, 0.0).rgb;
    float3 b = src.SampleLevel(uv + float2(0.0, -2.0) * texel, 0.0).rgb;
    float3 c = src.SampleLevel(uv + float2(2.0, -2.0) * texel, 0.0).rgb;
    float3 d = src.SampleLevel(uv + float2(-2.0, 0.0) * texel, 0.0).rgb;
    float3 e = src.SampleLevel(uv, 0.0).rgb;
    float3 f = src.SampleLevel(uv + float2(2.0, 0.0) * texel, 0.0).rgb;
    float3 g = src.SampleLevel(uv + float2(-2.0, 2.0) * texel, 0.0).rgb;
    float3 h = src.SampleLevel(uv + float2(0.0, 2.0) * texel, 0.0).rgb;
    float3 i = src.SampleLevel(uv + float2(2.0, 2.0) * texel, 0.0).rgb;
    float3 j = src.SampleLevel(uv + float2(-1.0, -1.0) * texel, 0.0).rgb;
    float3 k = src.SampleLevel(uv + float2(1.0, -1.0) * texel, 0.0).rgb;
    float3 l = src.SampleLevel(uv + float2(-1.0, 1.0) * texel, 0.0).rgb;
    float3 m = src.SampleLevel(uv + float2(1.0, 1.0) * texel, 0.0).rgb;
    return e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
}

// The same 13 taps with the prefilter and a Karis average over five 4-tap groups (first mip only).
float3 bloomDown13Prefilter(Sampler2D src, Sampler2D depth, float2 uv, float2 texel, BloomPush pc) {
    float3 a = bloomFetchPre(src, depth, uv + float2(-2.0, -2.0) * texel, pc);
    float3 b = bloomFetchPre(src, depth, uv + float2(0.0, -2.0) * texel, pc);
    float3 c = bloomFetchPre(src, depth, uv + float2(2.0, -2.0) * texel, pc);
    float3 d = bloomFetchPre(src, depth, uv + float2(-2.0, 0.0) * texel, pc);
    float3 e = bloomFetchPre(src, depth, uv, pc);
    float3 f = bloomFetchPre(src, depth, uv + float2(2.0, 0.0) * texel, pc);
    float3 g = bloomFetchPre(src, depth, uv + float2(-2.0, 2.0) * texel, pc);
    float3 h = bloomFetchPre(src, depth, uv + float2(0.0, 2.0) * texel, pc);
    float3 i = bloomFetchPre(src, depth, uv + float2(2.0, 2.0) * texel, pc);
    float3 j = bloomFetchPre(src, depth, uv + float2(-1.0, -1.0) * texel, pc);
    float3 k = bloomFetchPre(src, depth, uv + float2(1.0, -1.0) * texel, pc);
    float3 l = bloomFetchPre(src, depth, uv + float2(-1.0, 1.0) * texel, pc);
    float3 m = bloomFetchPre(src, depth, uv + float2(1.0, 1.0) * texel, pc);

    float3 g0 = (a + b + d + e) * 0.25;
    float3 g1 = (b + c + e + f) * 0.25;
    float3 g2 = (d + e + g + h) * 0.25;
    float3 g3 = (e + f + h + i) * 0.25;
    float3 g4 = (j + k + l + m) * 0.25;
    // Plain Jimenez weights, no Karis average: Karis (1 / (1 + luma)) exists to stop single-pixel fireflies of an HDR
    // source, and on this clipped-LDR source it is exactly the torch flame (a few clipped pixels) that it suppressed
    // to nothing. The source is already denoised and sanitised, so there are no fireflies left to guard against.
    return g0 * 0.125 + g1 * 0.125 + g2 * 0.125 + g3 * 0.125 + g4 * 0.5;
}

// 3x3 tent upsample (radius 1 texel of the source).
float3 bloomUp9(Sampler2D src, float2 uv, float2 texel) {
    float3 s = src.SampleLevel(uv, 0.0).rgb * 4.0;
    s += src.SampleLevel(uv + float2(-texel.x, 0.0), 0.0).rgb * 2.0;
    s += src.SampleLevel(uv + float2(texel.x, 0.0), 0.0).rgb * 2.0;
    s += src.SampleLevel(uv + float2(0.0, -texel.y), 0.0).rgb * 2.0;
    s += src.SampleLevel(uv + float2(0.0, texel.y), 0.0).rgb * 2.0;
    s += src.SampleLevel(uv + float2(-texel.x, -texel.y), 0.0).rgb;
    s += src.SampleLevel(uv + float2(texel.x, -texel.y), 0.0).rgb;
    s += src.SampleLevel(uv + float2(-texel.x, texel.y), 0.0).rgb;
    s += src.SampleLevel(uv + float2(texel.x, texel.y), 0.0).rgb;
    return s / 16.0;
}

// Camera-relative world direction of a pixel, from the un-jittered inverse view-projection.
float3 bloomPixelDir(int2 p, int2 size, BloomPush pc) {
    float2 ndc = (float2(p) + 0.5) / float2(size) * 2.0 - 1.0;
    if (pc.camOrigin.w > 0.5) {
        ndc.y = -ndc.y;
    }
    float4 w = mul(pc.invViewProj, float4(ndc, 0.5, 1.0));
    return normalize(w.xyz / w.w - pc.camOrigin.xyz);
}

// Fraction of the sun disk that is visible: sky pixels (ray distance >= 1e5) on the actual solar disk core around
// the projected sun position; 0 when the sun is occluded by foliage/terrain or outside the frame.
// Read from the unmodified frame: the prefilter stage samples it before the apply stage adds any glow, and parks the
// result in the alpha of mip 0. Reading the final image inside the apply dispatch raced with the neighbouring workgroups
// that were already adding the halo to the same sun pixels, so the glare flickered with the scheduling order.
float bloomSunVisibility(Sampler2D depth, Sampler2D frame, int2 size, BloomPush pc) {
    float2 ndc = pc.p1.zw;
    float edge = max(abs(ndc.x), abs(ndc.y));
    float fade = 1.0 - smoothstep(0.95, 1.25, edge);
    if (fade <= 0.0) {
        return 0.0;
    }
    float2 center = clamp((ndc * 0.5 + 0.5) * float2(size), float2(1.0), float2(size) - 1.0);
    float r = 0.014 * float(size.y);
    float vis = 0.0;
    float wSum = 0.0;
    for (int i = 0; i < 9; i++) {
        float2 o = float2(0.0);
        if (i != 0) {
            o = float2(cos(float(i) * 0.785398), sin(float(i) * 0.785398)) * r * (i < 5 ? 0.45 : 0.95);
        }
        float w = i == 0 ? 2.5 : (i < 5 ? 1.2 : 0.7);
        int2 t = clamp(int2(center + o), int2(0), size - 1);
        float sky = depth.Load(int3(t, 0)).r >= BLOOM_SKY_DISTANCE ? 1.0 : 0.0;
        float3 sc = frame.Load(int3(t, 0)).rgb;
        // Daytime blue sky has high blue (~0.9) but lower red/green; the sun disk is warm-white (all channels high).
        float sunLum = dot(sc, BLOOM_LUMA);
        float bright = smoothstep(0.88, 0.98, sunLum) * smoothstep(0.85, 0.96, sc.r);
        vis += sky * bright * w;
        wSum += w;
    }
    float rawVis = vis / max(wSum, 1.0e-4);
    return smoothstep(0.22, 0.75, rawVis) * fade;
}
