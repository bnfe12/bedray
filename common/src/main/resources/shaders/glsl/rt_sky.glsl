// rt_sky.glsl - the vanilla Minecraft sky for the RT passes (included by rt_common.glsl after StatsBuf / pc / flags).
//
// Everything comes from the game's own render state for this frame (RtSkyState.java writes it into the frame slot):
//  - sky disc colour (SkyRenderState.skyColor) blended into the fog colour (EnvironmentAttributes.FOG_COLOR) by the
//    vanilla sky fog: fog = distance to the sky disc (plane y = 16, radius 512) / FogSkyEnd (core/sky.fsh + fog.glsl,
//    SkyRenderer.buildSkyDisc(16));
//  - the sunrise / sunset fan (SkyRenderState.sunriseAndSunsetColor, SkyRenderer.renderSunriseAndSunset; approximated per
//    direction, see skySunsetFan) and the per-direction sunset tint of the fog colour (AtmosphericFogEnvironment.getBaseColor);
//  - sun (30 at distance 100) and moon (20 at distance 100, texture of the current MoonPhase) with the transform of
//    SkyRenderer.renderSunMoonAndStars: Ry(-90) * Rx(angle), quad at y = 100; textures from the resource packs (RtSkyTextures).
//    The sun sprite is added with its texture opacity and carries a separate body mask in alpha for stars / the specular disk;
//    the moon keeps the OVERLAY blend of BlendFunction 26.3 (src * srcAlpha + dst);
//  - stars: procedural point field rotated by starAngle, brightness starBrightness (vanilla: 1500 random quads);
//  - clouds: the clouds.png cells (12 x 4 x 12 blocks, colour LevelRenderState.cloudColor, face shades of core/clouds.vsh),
//    ray marched through the cloud slab (FANCY) or one plane (FAST), fading to FogCloudsEnd like core/clouds.fsh.
// The sky is built in display space (what the game would show) and converted to radiance with the inverse of the tone
// mapping (tonemapInverse / exposure), so the camera sees the vanilla colours and reflections stay consistent.

const float SKY_PI = 3.14159265358979323846;

uint skyFlags() { return StatsBuf(pc.statsAddr).skyTexFlags; }
bool skyHasSky() { return (skyFlags() & 16u) != 0u; }
bool skyIsEnd() { return (skyFlags() & 64u) != 0u; }
bool skyIsNether() { return (skyFlags() & 128u) != 0u; }
uint skyMoonPhase() { return skyFlags() & 7u; }
uint skyCloudStatus() { return (skyFlags() >> 8u) & 3u; }

// ---- display <-> radiance -------------------------------------------------------------------------------------------

float skyExposure() {
    if (((pc.modeBits >> 17u) & 1u) == 0u) {
        return 1.0;
    }
    float e = StatsBuf(pc.statsAddr).exposure;   // same as finishColor
    return (isnan(e) || isinf(e) || e <= 0.0) ? 1.1 : e;
}

// Display colour (what vanilla draws) -> radiance before finishColor.
vec3 displayToRadiance(vec3 c) {
    if (((pc.modeBits >> 17u) & 1u) == 0u) {
        return c;
    }
    return tonemapInverse(c) / skyExposure();
}

// ---- textures (RtSkyTextures: header slot * 4 = offset, w, h, loaded; pixels ABGR) ------------------------------------

bool skyTexLoaded(uint slot) {
    uvec2 a = StatsBuf(pc.statsAddr).skyTexAddr;
    if (a == uvec2(0u)) {
        return false;
    }
    return UIntBuf(a).v[slot * 4u + 3u] != 0u;
}

uvec2 skyTexSize(uint slot) {
    UIntBuf b = UIntBuf(StatsBuf(pc.statsAddr).skyTexAddr);
    return uvec2(b.v[slot * 4u + 1u], b.v[slot * 4u + 2u]);
}

vec4 skyTexelAt(uint slot, uvec2 t) {
    UIntBuf b = UIntBuf(StatsBuf(pc.statsAddr).skyTexAddr);
    uint off = b.v[slot * 4u];
    uint w = b.v[slot * 4u + 1u];
    uint c = b.v[off + t.y * w + t.x];
    return vec4(float(c & 255u), float((c >> 8u) & 255u), float((c >> 16u) & 255u), float(c >> 24u)) / 255.0;
}

// nearest texel (pixel art), uv in 0..1
vec4 skyTexel(uint slot, vec2 uv) {
    uvec2 s = skyTexSize(slot);
    uvec2 t = uvec2(clamp(uv, vec2(0.0), vec2(0.99999)) * vec2(s));
    return skyTexelAt(slot, t);
}

// ---- celestial bodies --------------------------------------------------------------------------------------------------

// World direction -> local frame of a body at `angle`: inverse of SkyRenderer's Ry(-90) * Rx(angle), i.e. Rx(-angle) * Ry(90).
vec3 celestialLocal(vec3 d, float angle) {
    if ((skyFlags() & 32u) != 0u) {
        d.x = -d.x;   // -Dvkbridge.rt.sunFlipX (RtSky.sunDirection)
    }
    // celestial tilt (RtSky.tiltRad): the sun / moon / star frame is the vanilla one rotated about X, undo it first
    float ct = cos(StatsBuf(pc.statsAddr).skyTilt);
    float st = sin(StatsBuf(pc.statsAddr).skyTilt);
    d = vec3(d.x, d.y * ct + d.z * st, -d.y * st + d.z * ct);
    float xr = d.z;    // Ry(90)
    float zr = -d.x;
    float ca = cos(angle);
    float sa = sin(angle);
    return vec3(xr, d.y * ca + zr * sa, -d.y * sa + zr * ca);
}

// Quad of half size `half` at distance 100 (SkyRenderer.applyCelestialBodyTransform(100, size)): local xz in -1..1, or
// outside (|p| > 1) when the direction misses the quad.
vec2 celestialQuad(vec3 d, float angle, float halfSize) {
    vec3 l = celestialLocal(d, angle);
    if (l.y <= 1.0e-3) {
        return vec2(4.0);
    }
    return l.xz * (100.0 / l.y) / halfSize;
}

// Sun texel for a direction: rgb is the sprite premultiplied by its texture opacity, alpha is the sun BODY (0 over the halo).
// Vanilla 26.3 sun.png is 32x32 and opaque in every texel, black in the corners: an 8x8 square core of (255,255,217) inside a
// round halo of (36,36,14). Vanilla's OVERLAY blend is src * srcAlpha + dst: premultiply here so resource-pack transparency is
// retained while the core stays square and the glow stays round. The specular disk uses only the core; its luminance is kept
// separately so multiplying it by the body mask cannot apply a resource pack's texture opacity twice.
vec4 skySunTexel(vec3 d, out float bodyLuminance) {
    bodyLuminance = 0.0;
    vec2 q = celestialQuad(d, StatsBuf(pc.statsAddr).skySunDir.w, 16.0);
    if (max(abs(q.x), abs(q.y)) > 1.0) {
        return vec4(0.0);
    }
    // buildCelestialQuad: (-1,-1) -> (u0, v0), (1, -1) -> (u1, v0), (1, 1) -> (u1, v1)
    vec2 uv = q * 0.5 + 0.5;
    float radius = length(q);
    // The sprite is opaque to its border, so the quad edge is faded by hand; the halo is round, so the fade is concentric.
    float feather = 1.0 - smoothstep(0.90, 1.0, radius);
    if (!skyTexLoaded(0u)) {
        // No resource pack file: a plain disc. The old fallback was a box, which is the "giant solid beige box" the mask that
        // used to sit here was fighting; that mask actually deleted the real halo, not the fallback.
        float m = (1.0 - smoothstep(0.25, 0.95, radius)) * feather;
        bodyLuminance = dot(vec3(1.0, 0.95, 0.78), vec3(0.2126, 0.7152, 0.0722)) * m * m;
        return vec4(vec3(1.0, 0.95, 0.78) * m, m);
    }
    vec4 t = skyTexel(0u, uv);
    float core = smoothstep(0.42, 0.85, max(t.r, max(t.g, t.b)));   // halo peak is 0.14, the core is saturated
    float opacity = t.a * feather;
    bodyLuminance = dot(t.rgb, vec3(0.2126, 0.7152, 0.0722)) * core * opacity;
    return vec4(t.rgb * opacity, core * opacity);
}

vec4 skySunTexel(vec3 d) {
    float bodyLuminance;
    return skySunTexel(d, bodyLuminance);
}

vec4 skyMoonTexel(vec3 d) {
    vec2 q = celestialQuad(d, StatsBuf(pc.statsAddr).skyMoonDir.w, 20.0);
    if (max(abs(q.x), abs(q.y)) > 1.0) {
        return vec4(0.0);
    }
    // buildMoonPhases: (-1,-1) -> (u1, v1), (1, -1) -> (u0, v1), (1, 1) -> (u0, v0)
    vec2 uv = 0.5 - q * 0.5;
    uint slot = 1u + skyMoonPhase();
    return skyTexLoaded(slot) ? skyTexel(slot, uv) : vec4(0.75, 0.78, 0.85, 1.0);
}

uint skyHash(uvec3 v) {
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
float skyStars(vec3 d) {
    vec3 l = celestialLocal(d, StatsBuf(pc.statsAddr).skyMisc.x);
    vec3 a = abs(l);
    uint face;
    vec2 uv;
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
    vec2 g = (uv * 0.5 + 0.5) * N;
    vec2 cell = floor(g);
    uint h = skyHash(uvec3(uvec2(cell), face));
    if ((h & 1023u) >= 10u) {
        return 0.0;
    }
    vec2 c = vec2(0.3 + 0.4 * float((h >> 10u) & 255u) / 255.0, 0.3 + 0.4 * float((h >> 18u) & 255u) / 255.0);
    float size = 0.12 + 0.08 * float((h >> 26u) & 63u) / 63.0;   // cell = 2/160 rad: 0.0015 .. 0.0025 rad
    vec2 f = g - cell - c;
    return max(abs(f.x), abs(f.y)) < size ? 1.0 : 0.0;
}

// ---- clouds -----------------------------------------------------------------------------------------------------------

// Cell of clouds.png at integer cloud cell (wrapped), rgba; a < 10/255 = empty (CloudRenderer.isCellEmpty).
vec4 skyCloudCell(ivec2 cell, uvec2 size) {
    uvec2 t = uvec2((cell % ivec2(size) + ivec2(size)) % ivec2(size));
    return skyTexelAt(9u, t);
}

// Vanilla clouds seen along d from the camera (display rgb, alpha). Space: cloud texture blocks, x/z wrapped as in
// CloudRenderer.render, y relative to the cloud bottom (LevelRenderState.cloudHeight); cells 12 x 4 x 12.
vec4 skyClouds(vec3 d) {
    uint status = skyCloudStatus();
    if (status == 0u || !skyTexLoaded(9u)) {
        return vec4(0.0);
    }
    StatsBuf sb = StatsBuf(pc.statsAddr);
    vec4 cloudColor = sb.skyCloudColor;
    vec3 o = sb.skyCloudPos.xyz;
    float fadeEnd = max(sb.skyCloudPos.w, 1.0);
    uvec2 size = skyTexSize(9u);
    float top = status == 2u ? 4.0 : 0.0;
    // slab [0, top] (FAST: the plane y = 0)
    float t0;
    float t1;
    if (abs(d.y) < 1.0e-5) {
        if (o.y < 0.0 || o.y > top) {
            return vec4(0.0);
        }
        t0 = 0.0;
        t1 = fadeEnd;
    } else {
        float ta = (0.0 - o.y) / d.y;
        float tb = (top - o.y) / d.y;
        t0 = max(min(ta, tb), 0.0);
        t1 = min(max(ta, tb), fadeEnd);
    }
    if (status == 1u) {
        t1 = t0;   // FAST: single plane
    }
    if (t0 > t1 + 1.0e-4 || t0 >= fadeEnd) {
        return vec4(0.0);
    }
    // 2D DDA over 12 x 12 cells from t0 to t1
    vec3 p = o + d * t0;
    ivec2 cell = ivec2(floor(p.xz / 12.0));
    vec2 dxz = d.xz;
    ivec2 stepI = ivec2(dxz.x >= 0.0 ? 1 : -1, dxz.y >= 0.0 ? 1 : -1);
    vec2 inv = vec2(abs(dxz.x) > 1.0e-8 ? 1.0 / dxz.x : 1.0e30, abs(dxz.y) > 1.0e-8 ? 1.0 / dxz.y : 1.0e30);
    vec2 nextB = (vec2(cell) + vec2(greaterThanEqual(dxz, vec2(0.0)))) * 12.0;
    vec2 tMax = t0 + (nextB - p.xz) * inv;
    vec2 tDelta = abs(12.0 * inv);
    // face of the entry: 0 bottom, 1 top, 2 north/south (z), 3 west/east (x)
    uint face = (o.y < 0.0) ? 0u : (o.y > top ? 1u : 3u);
    float t = t0;
    for (int i = 0; i < 320; i++) {
        vec4 c = skyCloudCell(cell, size);
        if (c.a >= 10.0 / 255.0) {
            float shade = face == 0u ? 0.7 : (face == 1u ? 1.0 : (face == 2u ? 0.8 : 0.9));   // core/clouds.vsh faceColors
            if (status == 1u) {
                shade = 1.0;   // FAST draws the flat layer with the top colour
            }
            float fog = clamp(t / fadeEnd, 0.0, 1.0);   // core/clouds.fsh: alpha *= 1 - linear_fog_value(d, 0, FogCloudsEnd)
            return vec4(c.rgb * shade * cloudColor.rgb, c.a * cloudColor.a * (1.0 - fog));
        }
        if (tMax.x < tMax.y) {
            t = tMax.x;
            tMax.x += tDelta.x;
            cell.x += stepI.x;
            face = 3u;
        } else {
            t = tMax.y;
            tMax.y += tDelta.y;
            cell.y += stepI.y;
            face = 2u;
        }
        if (t > t1) {
            break;
        }
    }
    return vec4(0.0);
}

// ---- sky colour -------------------------------------------------------------------------------------------------------

// Fog (horizon) colour for a view direction: FOG_COLOR, tinted by the sunrise colour towards the sun side
// (AtmosphericFogEnvironment.getBaseColor does it for the camera forward vector; here per direction, linear blend).
vec3 skyFogColor(vec3 d) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    vec3 fog = sb.skyFogColor.rgb;
    vec4 ss = sb.skySunset;
    if (ss.a > 0.0) {
        float sunX = sin(sb.skySunDir.w) > 0.0 ? -1.0 : 1.0;
        if ((skyFlags() & 32u) != 0u) {
            sunX = -sunX;
        }
        vec2 hz = vec2(d.x, d.z);
        float hz2 = dot(hz, hz);
        hz = hz2 > 1.0e-12 ? hz * inversesqrt(hz2) : vec2(1.0, 0.0);   // straight down/up: any horizon direction is a stand-in
        float look = max(0.0, hz.x * sunX);
        fog = mix(fog, ss.rgb, look * ss.a);
    }
    return fog;
}

// The sunrise / sunset fan (SkyRenderer.buildSunriseFan + renderSunriseAndSunset): centre on the horizon towards the sun
// side (alpha = colour.a), fading to 0 at the rim, which reaches ~90 deg in azimuth and atan(40a/120) in elevation.
float skySunsetFan(vec3 d) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    float alpha = sb.skySunset.a;
    if (alpha <= 0.001) {
        return 0.0;
    }
    float sunX = sin(sb.skySunDir.w) < 0.0 ? 1.0 : -1.0;
    if ((skyFlags() & 32u) != 0u) {
        sunX = -sunX;
    }
    float horiz = length(d.xz);
    float az = acos(clamp(d.x * sunX / max(horiz, 1.0e-5), -1.0, 1.0));   // 0 at the fan centre
    float el = atan(d.y, horiz);
    float elMax = atan(40.0 * alpha / 120.0) + 0.02;
    float r = length(vec2(az / (0.5 * SKY_PI), max(el, 0.0) / elMax));
    return alpha * clamp(1.0 - r, 0.0, 1.0);
}

// Inverted End sky cube (textures/environment/end_sky.png, slot 10) tiled 16 times per face
vec3 skyEndCube(vec3 d) {
    vec3 ad = abs(d);
    float m = max(ad.x, max(ad.y, ad.z));
    if (m < 1.0e-5) return vec3(0.0);
    vec3 p = d / m;
    vec2 uv;
    if (ad.y >= ad.x && ad.y >= ad.z) {
        if (p.y < 0.0) {
            uv = vec2((p.x + 1.0) * 8.0, (p.z + 1.0) * 8.0);
        } else {
            uv = vec2((p.x + 1.0) * 8.0, (-p.z + 1.0) * 8.0);
        }
    } else if (ad.z >= ad.x) {
        if (p.z > 0.0) {
            uv = vec2((p.x + 1.0) * 8.0, (p.y + 1.0) * 8.0);
        } else {
            uv = vec2((p.x + 1.0) * 8.0, (-p.y + 1.0) * 8.0);
        }
    } else {
        if (p.x > 0.0) {
            uv = vec2((-p.z + 1.0) * 8.0, (p.y + 1.0) * 8.0);
        } else {
            uv = vec2((p.z + 1.0) * 8.0, (p.y + 1.0) * 8.0);
        }
    }
    vec2 wrappedUv = fract(uv);
    vec4 tex = skyTexLoaded(10u) ? skyTexel(10u, wrappedUv) : vec4(0.15, 0.10, 0.20, 1.0);
    // Vanilla vertex color tint: 0xFF282828 (40/255 = 0.15686)
    return tex.rgb * 0.15686;
}

// Celestial End Flash quad (textures/environment/celestial/end_flash.png, slot 11)
vec4 skyEndFlashTexel(vec3 d, float intensity, float xAngle, float yAngle) {
    if (intensity <= 1.0e-5) return vec4(0.0);
    // Inverse of SkyRenderer: rotate Y by (yAngle - 180), then rotate X by (90 + xAngle)
    float radY = radians(yAngle - 180.0);
    float cy = cos(radY), sy = sin(radY);
    vec3 dy = vec3(d.x * cy + d.z * sy, d.y, -d.x * sy + d.z * cy);
    float radX = radians(90.0 + xAngle);
    float cx = cos(radX), sx = sin(radX);
    vec3 l = vec3(dy.x, dy.y * cx - dy.z * sx, dy.y * sx + dy.z * cx);
    if (l.y <= 1.0e-3) return vec4(0.0);
    // Quad at height 100 with half-size 60:
    vec2 p = l.xz * (100.0 / l.y) / 60.0;
    if (abs(p.x) > 1.0 || abs(p.y) > 1.0) return vec4(0.0);
    vec2 uv = p * 0.5 + 0.5;
    vec4 tex = skyTexLoaded(11u) ? skyTexel(11u, uv) : vec4(0.9, 0.7, 1.0, 1.0);
    return vec4(tex.rgb * intensity, tex.a * intensity);
}

// Full vanilla sky in display space (what the camera sees on a miss).
vec3 skyDisplay(vec3 dir) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (skyIsEnd()) {
        vec3 c = skyEndCube(dir);
        // Starry cosmic skybox dots
        float stars = skyStars(dir);
        c += vec3(stars * 0.45);
        // End Flash if active
        float intensity = sb.skySunset.x;
        if (intensity > 1.0e-5) {
            vec4 flash = skyEndFlashTexel(dir, intensity, sb.skySunset.y, sb.skySunset.z);
            c += flash.rgb * (flash.a * 3.5);
        }
        // Biome fog blend at the horizon / bottom
        float horizBlend = clamp(1.0 - abs(dir.y), 0.0, 1.0);
        c = mix(c, sb.skyFogColor.rgb, horizBlend * 0.35);
        return c;
    }
    if (skyIsNether()) {
        return sb.skyFogColor.rgb;   // Nether: rich atmospheric biome fog
    }
    if (!skyHasSky()) {
        return sb.skyFogColor.rgb;   // Other dimensions without skybox: biome fog
    }
    vec3 fog = skyFogColor(dir);
    vec3 c;
    if (dir.y <= 0.0) {
        c = fog;
    } else {
        // sky disc at y = 16, radius 512; spherical distance from the camera, fog = dist / FogSkyEnd
        float r = 16.0 * length(dir.xz) / dir.y;
        float dist = r > 512.0 ? 1.0e6 : length(vec2(r, 16.0));
        float f = clamp(dist / max(sb.skyMisc.y, 1.0), 0.0, 1.0);
        c = mix(pc.skyColor.rgb, fog, f);   // sky disc colour = SkyRenderState.skyColor (push constant)
    }
    // sunrise fan (translucent)
    float fan = skySunsetFan(dir);
    c = mix(c, sb.skySunset.rgb, fan);
    float rain = sb.skyMisc.w;
    vec4 cl = skyClouds(dir);
    // sun (+= premultiplied rgb), moon (OVERLAY: += rgb * a), stars (+= b * b); all below the horizon fog / clouds
    if (dir.y > -0.05) {
        vec4 sun = skySunTexel(dir);
        c += sun.rgb * rain;   // rgb already includes texture opacity; alpha is the body mask, not another opacity multiplier
        vec4 moon = skyMoonTexel(dir);
        c += moon.rgb * moon.a * rain;
        float day = pc.keyDir.w;
        float nightStars = (1.0 - smoothstep(0.05, 0.25, day)) * (1.0 - cl.a) * rain;
        float sb2 = max(sb.skyFogColor.w * sb.skyFogColor.w, nightStars * 0.25);
        // stars behind the sun body only: the halo is added light, it does not hide what is behind it
        if (sb2 > 0.0 && sun.a < 1.0 && moon.a == 0.0) {
            c += vec3(skyStars(dir) * sb2 * (1.0 - sun.a));
        }
    }
    c = mix(c, cl.rgb, cl.a);
    return c;
}

// Radiance of the sky along a camera / mirror ray (the display sky through the inverse tone mapping).
vec3 skyAt(vec3 dir) {
    return displayToRadiance(skyDisplay(dir));
}

// Radiance of the key light body (sun by day, moon by night) for specular paths, without the sky behind it: the textured
// square scaled to the key light colour (the diffuse light of the body is sampled explicitly, NEE).
vec3 sunDisk(vec3 dir) {
    StatsBuf sb = StatsBuf(pc.statsAddr);
    if (skyIsEnd()) {
        float intensity = sb.skySunset.x;
        if (intensity > 1.0e-5) {
            vec4 flash = skyEndFlashTexel(dir, intensity, sb.skySunset.y, sb.skySunset.z);
            return flash.rgb * (flash.a * 8.0);
        }
        return vec3(0.0);
    }
    if (!skyHasSky()) {
        return vec3(0.0);
    }
    bool keyIsSun = dot(pc.keyDir.xyz, sb.skySunDir.xyz) > 0.999;
    // The sprites vanish with the weather (SkyRenderState.rainBrightness is the alpha vanilla draws them with, and the display
    // composite above multiplies by it), so a body that is not in the sky must not glint in water or glass either.
    float rain = clamp(sb.skyMisc.w, 0.0, 1.0);
    if (keyIsSun) {
        float bodyLuminance;
        skySunTexel(dir, bodyLuminance);
        return pc.keyColor.rgb * (bodyLuminance * 6.0 * rain);
    }
    vec4 t = skyMoonTexel(dir);
    float lum = dot(t.rgb, vec3(0.2126, 0.7152, 0.0722));
    return pc.keyColor.rgb * (t.a * lum * 6.0 * rain);
}
