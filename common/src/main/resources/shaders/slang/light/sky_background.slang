// Preserved vanilla background composition from rt_sky.glsl. It is display
// colour, never the diffuse lighting dome or a new discrete light source.
public module sky_background_display;
__exported import "light/sky_celestial.slang";
import "light/sky_clouds.slang";
import "tonemap_curve.slang";
import "contracts/math.slang";

static const float SKY_PI = 3.14159265358979323846;

float3 skyFogColor(SkyDisplayState sky, float3 d) {
    float3 fog = sky.skyFogColor.rgb;
    float4 ss = sky.skySunset;
    if (ss.a > 0.0) {
        float sunX = sin(sky.skySunDir.w) > 0.0 ? -1.0 : 1.0;
        if ((skyFlags(sky) & 32u) != 0u) {
            sunX = -sunX;
        }
        float2 hz = float2(d.x, d.z);
        float hz2 = dot(hz, hz);
        hz = hz2 > 1.0e-12 ? hz * rsqrt(hz2) : float2(1.0, 0.0);   // straight down/up: any horizon direction is a stand-in
        float look = max(0.0, hz.x * sunX);
        fog = lerp(fog, ss.rgb, look * ss.a);
    }
    return fog;
}

// The sunrise / sunset fan (SkyRenderer.buildSunriseFan + renderSunriseAndSunset): centre on the horizon towards the sun
// side (alpha = colour.a), fading to 0 at the rim, which reaches ~90 deg in azimuth and atan(40a/120) in elevation.
float skySunsetFan(SkyDisplayState sky, float3 d) {
    float alpha = sky.skySunset.a;
    if (alpha <= 0.001) {
        return 0.0;
    }
    float sunX = sin(sky.skySunDir.w) < 0.0 ? 1.0 : -1.0;
    if ((skyFlags(sky) & 32u) != 0u) {
        sunX = -sunX;
    }
    float horiz = length(d.xz);
    float az = acos(clamp(d.x * sunX / max(horiz, 1.0e-5), -1.0, 1.0));   // 0 at the fan centre
    float el = atan2(d.y, horiz);
    float elMax = atan(40.0 * alpha / 120.0) + 0.02;
    float r = length(float2(az / (0.5 * SKY_PI), max(el, 0.0) / elMax));
    return alpha * clamp(1.0 - r, 0.0, 1.0);
}

// Inverted End sky cube (textures/environment/end_sky.png, slot 10) tiled 16 times per face
float3 skyEndCube(SkyDisplayState sky, float3 d) {
    float3 ad = abs(d);
    float m = max(ad.x, max(ad.y, ad.z));
    if (m < 1.0e-5) return float3(0.0);
    float3 p = d / m;
    float2 uv;
    if (ad.y >= ad.x && ad.y >= ad.z) {
        if (p.y < 0.0) {
            uv = float2((p.x + 1.0) * 8.0, (p.z + 1.0) * 8.0);
        } else {
            uv = float2((p.x + 1.0) * 8.0, (-p.z + 1.0) * 8.0);
        }
    } else if (ad.z >= ad.x) {
        if (p.z > 0.0) {
            uv = float2((p.x + 1.0) * 8.0, (p.y + 1.0) * 8.0);
        } else {
            uv = float2((p.x + 1.0) * 8.0, (-p.y + 1.0) * 8.0);
        }
    } else {
        if (p.x > 0.0) {
            uv = float2((-p.z + 1.0) * 8.0, (p.y + 1.0) * 8.0);
        } else {
            uv = float2((p.z + 1.0) * 8.0, (p.y + 1.0) * 8.0);
        }
    }
    float2 wrappedUv = frac(uv);
    float4 tex = skyTexLoaded(sky, 10u) ? skyTexel(sky, 10u, wrappedUv) : float4(0.15, 0.10, 0.20, 1.0);
    // Vanilla vertex color tint: 0xFF282828 (40/255 = 0.15686)
    return tex.rgb * 0.15686;
}

// Celestial End Flash quad (textures/environment/celestial/end_flash.png, slot 11)
float4 skyEndFlashTexel(SkyDisplayState sky, float3 d, float intensity, float xAngle, float yAngle) {
    if (intensity <= 1.0e-5) return float4(0.0);
    // Inverse of SkyRenderer: rotate Y by (yAngle - 180), then rotate X by (90 + xAngle)
    float radY = radians(yAngle - 180.0);
    float cy = cos(radY), sy = sin(radY);
    float3 dy = float3(d.x * cy + d.z * sy, d.y, -d.x * sy + d.z * cy);
    float radX = radians(90.0 + xAngle);
    float cx = cos(radX), sx = sin(radX);
    float3 l = float3(dy.x, dy.y * cx - dy.z * sx, dy.y * sx + dy.z * cx);
    if (l.y <= 1.0e-3) return float4(0.0);
    // Quad at height 100 with half-size 60:
    float2 p = l.xz * (100.0 / l.y) / 60.0;
    if (abs(p.x) > 1.0 || abs(p.y) > 1.0) return float4(0.0);
    float2 uv = p * 0.5 + 0.5;
    float4 tex = skyTexLoaded(sky, 11u) ? skyTexel(sky, 11u, uv) : float4(0.9, 0.7, 1.0, 1.0);
    return float4(tex.rgb * intensity, tex.a * intensity);
}

// Full vanilla sky in display space (what the camera sees on a miss).
float3 skyDisplay(SkyDisplayState sky, float3 dir, bool includeBodies) {
    if (skyIsEnd(sky)) {
        float3 c = skyEndCube(sky, dir);
        // Starry cosmic skybox dots
        float stars = skyStars(sky, dir);
        c += float3(stars * 0.45);
        // End Flash if active
        float intensity = sky.skySunset.x;
        if (intensity > 1.0e-5) {
            float4 flash = skyEndFlashTexel(sky, dir, intensity, sky.skySunset.y, sky.skySunset.z);
            c += flash.rgb * (flash.a * 3.5);
        }
        // Biome fog blend at the horizon / bottom
        float horizBlend = clamp(1.0 - abs(dir.y), 0.0, 1.0);
        c = lerp(c, sky.skyFogColor.rgb, horizBlend * 0.35);
        return c;
    }
    if (skyIsNether(sky)) {
        return sky.skyFogColor.rgb;   // Nether: rich atmospheric biome fog
    }
    if (!skyHasSky(sky)) {
        return sky.skyFogColor.rgb;   // Other dimensions without skybox: biome fog
    }
    float3 fog = skyFogColor(sky, dir);
    float3 c;
    if (dir.y <= 0.0) {
        c = fog;
    } else {
        // sky disc at y = 16, radius 512; spherical distance from the camera, fog = dist / FogSkyEnd
        float r = 16.0 * length(dir.xz) / dir.y;
        float dist = r > 512.0 ? 1.0e6 : length(float2(r, 16.0));
        float f = clamp(dist / max(sky.skyMisc.y, 1.0), 0.0, 1.0);
        c = lerp(sky.visibleColorDay.rgb, fog, f);   // sky disc colour = SkyRenderState.skyColor (push constant)
    }
    // sunrise fan (translucent)
    float fan = skySunsetFan(sky, dir);
    c = lerp(c, sky.skySunset.rgb, fan);
    float rain = sky.skyMisc.w;
    float4 cl = skyClouds(sky, dir);
    // sun (+= premultiplied rgb), moon (OVERLAY: += rgb * a), stars (+= b * b); all below the horizon fog / clouds
    if (dir.y > -0.05) {
        float4 sun = skySunTexel(sky, dir);
        if (includeBodies) c += sun.rgb * rain;   // rgb already includes texture opacity; alpha is the body mask, not another opacity multiplier
        float4 moon = skyMoonTexel(sky, dir);
        if (includeBodies) c += moon.rgb * moon.a * rain;
        float day = sky.visibleColorDay.w;
        float nightStars = (1.0 - smoothstep(0.05, 0.25, day)) * (1.0 - cl.a) * rain;
        float sb2 = max(sky.skyFogColor.w * sky.skyFogColor.w, nightStars * 0.25);
        // stars behind the sun body only: the halo is added light, it does not hide what is behind it
        if (sb2 > 0.0 && sun.a < 1.0 && moon.a == 0.0) {
            c += float3(skyStars(sky, dir) * sb2 * (1.0 - sun.a));
        }
    }
    c = lerp(c, cl.rgb, cl.a);
    return c;
}

// The typed display applies exposure in both output modes. Divide it out here
// so the preserved display-space background survives that final conversion.
float3 skyBackgroundRadiance(SkyDisplayState sky, float3 direction, bool includeBodies) {
    float3 display = skyDisplay(sky, direction, includeBodies);
    float exposure = isFinite(sky.exposure) && sky.exposure > 0.0 ? sky.exposure : 1.1;
    return ((skyFlags(sky) & SKY_DISPLAY_TONEMAPPED) != 0u ? tonemapInverse(display) : display) / exposure;
}
