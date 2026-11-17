// Shadows of the vanilla cloud layer on the key light.
//
// The sky already draws these clouds (sky_clouds.slang: a 12 x 12 block cell mask, FANCY slab 4 blocks thick or the FAST
// plane at the cloud bottom).  This module answers the other half: how much of the sun or moon a point loses on its way
// through that layer.  It reads the same texture cells with the same wrapping, so a shadow lies under the cloud that
// casts it and moves with it.  The strength rides in bits 12..19 of the sky packet flags (percent, 0 = off), so nothing
// about the packet layout, the push blocks or the descriptor set changes.

public module cloud_shadow;
import "contracts/math.slang";
import "light/sky_clouds.slang";

static const uint  CLOUD_SHADOW_PERCENT_SHIFT = 12u;
static const float CLOUD_SHADOW_SIGMA = 0.28;        // extinction per block of cloud on the ray; 4 blocks straight up keep 33 %
static const float CLOUD_SHADOW_FAST_THICKNESS = 3.0; // the flat FAST layer stands in for a slab of this thickness
static const float CLOUD_SHADOW_MIN_ELEVATION = 0.02; // a light at the horizon is gone behind the terrain long before this
static const uint  CLOUD_SHADOW_SAMPLES = 4u;

// What the sampler carries: the cloud texture (a raw address, no descriptor), the flags, the cloud position and its opacity.
// All plain data; a zeroed value means "no clouds, no shadow" and no pass that leaves it zero ever reads memory.
struct CloudShadowSource {
    uint64_t textureAddress;
    uint textureWords;
    uint flags;
    float4 position;     // xyz: the camera in cloud space (x/z wrapped, y above the cloud bottom), w: fade-out distance
    float opacity;       // alpha of the cloud colour (rain and darkness change it)
};

CloudShadowSource cloudShadowSource(SkyDisplayState sky) {
    CloudShadowSource source = (CloudShadowSource)0;
    if (!sky.valid) return source;
    source.textureAddress = sky.textureAddress;
    source.textureWords = sky.textureWords;
    source.flags = sky.skyTexFlags;
    source.position = sky.skyCloudPos;
    source.opacity = clamp(sky.skyCloudColor.w, 0.0, 1.0);
    return source;
}

// Fraction of the light that survives from `position` (camera relative) along `direction`, 1 = no cloud in the way.
float cloudShadowTransmittance(CloudShadowSource source, float3 position, float3 direction) {
    uint percent = (source.flags >> CLOUD_SHADOW_PERCENT_SHIFT) & 0xFFu;
    uint status = (source.flags >> 8u) & 3u;
    if (percent == 0u || status == 0u || source.textureAddress == 0u || !(direction.y > CLOUD_SHADOW_MIN_ELEVATION))
        return 1.0;

    SkyDisplayState sky = (SkyDisplayState)0;
    sky.valid = true;
    sky.textureAddress = source.textureAddress;
    sky.textureWords = source.textureWords;
    sky.skyTexFlags = source.flags;
    uint2 size = skyTexSize(sky, 9u);
    if (any(size == uint2(0u))) return 1.0;

    float3 o = source.position.xyz + position;
    float top = status == 2u ? 4.0 : 0.0;
    if (!(o.y < top) || !isFinite(o)) return 1.0;           // at or above the layer: nothing overhead

    float tIn = max(-o.y / direction.y, 0.0);
    float tOut = status == 2u ? (top - o.y) / direction.y : tIn;
    float span = max(tOut - tIn, 0.0);

    float covered = 0.0;
    float samples = status == 2u ? float(CLOUD_SHADOW_SAMPLES) : 1.0;
    float tMid = tIn + 0.5 * span;
    for (uint i = 0u; i < (status == 2u ? CLOUD_SHADOW_SAMPLES : 1u); ++i) {
        float t = status == 2u ? tIn + (float(i) + 0.5) / float(CLOUD_SHADOW_SAMPLES) * span : tIn;
        float3 q = o + direction * t;
        int2 cell = int2(floor(q.xz / 12.0));
        if (skyCloudCell(sky, cell, size).a >= 10.0 / 255.0) covered += 1.0;
    }
    if (covered <= 0.0) return 1.0;

    float depth = CLOUD_SHADOW_SIGMA * (status == 2u ? span : CLOUD_SHADOW_FAST_THICKNESS) * (covered / samples);
    // The layer fades out with distance exactly like the picture of it does.
    float fade = 1.0 - clamp(length(position + direction * tMid) / max(source.position.w, 1.0), 0.0, 1.0);
    float strength = float(percent) * 0.01 * fade * source.opacity;
    float transmittance = exp(-depth * strength);
    return isFinite(transmittance) ? clamp(transmittance, 0.0, 1.0) : 1.0;
}
