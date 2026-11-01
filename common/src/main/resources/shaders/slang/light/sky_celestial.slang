// Preserved celestial sprite transforms, phase selection and stars from rt_sky.glsl.
// Display bodies are separate from the key light sampled by NEE.
public module sky_celestial_display;
__exported import "light/sky_textures.slang";

float3 celestialLocal(SkyDisplayState sky, float3 d, float angle) {
    if ((skyFlags(sky) & 32u) != 0u) {
        d.x = -d.x;   // -Dvkbridge.rt.sunFlipX (RtSky.sunDirection)
    }
    // celestial tilt (RtSky.tiltRad): the sun / moon / star frame is the vanilla one rotated about X, undo it first
    float ct = cos(sky.skyTilt);
    float st = sin(sky.skyTilt);
    d = float3(d.x, d.y * ct + d.z * st, -d.y * st + d.z * ct);
    float xr = d.z;    // Ry(90)
    float zr = -d.x;
    float ca = cos(angle);
    float sa = sin(angle);
    return float3(xr, d.y * ca + zr * sa, -d.y * sa + zr * ca);
}

// Quad of half size `half` at distance 100 (SkyRenderer.applyCelestialBodyTransform(100, size)): local xz in -1..1, or
// outside (|p| > 1) when the direction misses the quad.
float2 celestialQuad(SkyDisplayState sky, float3 d, float angle, float halfSize) {
    float3 l = celestialLocal(sky, d, angle);
    if (l.y <= 1.0e-3) {
        return float2(4.0);
    }
    return l.xz * (100.0 / l.y) / halfSize;
}

// Sun texel for a direction: rgb is the sprite premultiplied by its texture opacity, alpha is the sun BODY (0 over the halo).
// Vanilla 26.3 sun.png is 32x32 and opaque in every texel, black in the corners: an 8x8 square core of (255,255,217) inside a
// round halo of (36,36,14). Vanilla's OVERLAY blend is src * srcAlpha + dst: premultiply here so resource-pack transparency is
// retained while the core stays square and the glow stays round. Alpha is a body
// mask for stars; it must not multiply the already premultiplied RGB again.
float4 skySunTexel(SkyDisplayState sky, float3 d) {
    float2 q = celestialQuad(sky, d, sky.skySunDir.w, 16.0);
    if (max(abs(q.x), abs(q.y)) > 1.0) {
        return float4(0.0);
    }
    // buildCelestialQuad: (-1,-1) -> (u0, v0), (1, -1) -> (u1, v0), (1, 1) -> (u1, v1)
    float2 uv = q * 0.5 + 0.5;
    float radius = length(q);
    // The sprite is opaque to its border, so the quad edge is faded by hand; the halo is round, so the fade is concentric.
    float feather = 1.0 - smoothstep(0.90, 1.0, radius);
    if (!skyTexLoaded(sky, 0u)) {
        // No resource pack file: a plain disc. The old fallback was a box, which is the "giant solid beige box" the mask that
        // used to sit here was fighting; that mask actually deleted the real halo, not the fallback.
        float m = (1.0 - smoothstep(0.25, 0.95, radius)) * feather;
        return float4(float3(1.0, 0.95, 0.78) * m, m);
    }
    float4 t = skyTexel(sky, 0u, uv);
    float core = smoothstep(0.42, 0.85, max(t.r, max(t.g, t.b)));   // halo peak is 0.14, the core is saturated
    float opacity = t.a * feather;
    return float4(t.rgb * opacity, core * opacity);
}

float4 skyMoonTexel(SkyDisplayState sky, float3 d) {
    float2 q = celestialQuad(sky, d, sky.skyMoonDir.w, 20.0);
    if (max(abs(q.x), abs(q.y)) > 1.0) {
        return float4(0.0);
    }
    // buildMoonPhases: (-1,-1) -> (u1, v1), (1, -1) -> (u0, v1), (1, 1) -> (u0, v0)
    float2 uv = 0.5 - q * 0.5;
    uint slot = 1u + skyMoonPhase(sky);
    return skyTexLoaded(sky, slot) ? skyTexel(sky, slot, uv) : float4(0.75, 0.78, 0.85, 1.0);
}

uint skyHash(uint3 v) {
    uint h = v.x * 73856093u ^ v.y * 19349663u ^ v.z * 83492791u;
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    h ^= h >> 16u;
    return h;
}

// Star field (1 on a star): cube map grid of 160 x 160 cells per face in the star frame (starAngle), ~1 % of the cells
// hold one square star of 0.0015..0.0025 rad half size (vanilla: 0.15..0.25 at distance 100, 1500 stars).
float skyStars(SkyDisplayState sky, float3 d) {
    float3 l = celestialLocal(sky, d, sky.skyMisc.x);
    float3 a = abs(l);
    uint face;
    float2 uv;
    if (a.x >= a.y && a.x >= a.z) {
        face = l.x > 0.0 ? 0u : 1u;
        uv = l.yz / a.x;
    } else if (a.y >= a.z) {
        face = l.y > 0.0 ? 2u : 3u;
        uv = l.xz / a.y;
    } else {
        face = l.z > 0.0 ? 4u : 5u;
        uv = l.xy / a.z;
    }
    const float N = 160.0;
    float2 g = (uv * 0.5 + 0.5) * N;
    float2 cell = floor(g);
    uint h = skyHash(uint3(uint2(cell), face));
    if ((h & 1023u) >= 10u) {
        return 0.0;
    }
    float2 c = float2(0.3 + 0.4 * float((h >> 10u) & 255u) / 255.0, 0.3 + 0.4 * float((h >> 18u) & 255u) / 255.0);
    float size = 0.12 + 0.08 * float((h >> 26u) & 63u) / 63.0;   // cell = 2/160 rad: 0.0015 .. 0.0025 rad
    float2 f = g - cell - c;
    return max(abs(f.x), abs(f.y)) < size ? 1.0 : 0.0;
}
