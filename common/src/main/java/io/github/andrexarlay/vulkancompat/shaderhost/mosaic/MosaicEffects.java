package io.github.andrexarlay.vulkancompat.shaderhost.mosaic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Built-in post effects. Each one is an ordinary {@link MosaicFunction} with readable GLSL, so it is inserted like any
 * node, and an author can open its code, learn from it and change it (a changed body is simply their own node).
 * Node code may call the helpers every generated shader provides: {@code mosaicScene(uv)} (world color),
 * {@code mosaicDepth(uv)} (primary-ray distance, 1e6 = sky), {@code mosaicTrace(origin, dir, tMax)} (hit distance or
 * -1), {@code mosaicShadow(origin, dir)} (1 = unblocked), {@code mosaicEye()}, {@code mosaicViewDir(uv)} and the
 * {@code frame} block (timing.x = seconds, viewSize.xy = pixels, viewSize.zw = one pixel in uv).
 */
public final class MosaicEffects {

    private MosaicEffects() {}

    private static final Map<String, MosaicFunction> ALL = new LinkedHashMap<>();
    private static final Map<String, String> SLANG = slangBodies();

    private static void add(String signature, String label, String help, String body) {
        add("Effects", signature, label, help, body);
    }

    private static void add(String category, String signature, String label, String help, String body) {
        MosaicFunction f = MosaicFunction.parseSignature(signature, body);
        ALL.put(f.name(), new MosaicFunction(f.name(), label, category, help, f.params(), f.output(), body, SLANG.get(f.name())));
    }


    /** Hand-written Slang bodies where the automatic GLSL translation does not apply (matrix products). */
    private static Map<String, String> slangBodies() { return Map.of("rtReflections", """
        float m = mosaicHitMaterial(uv);
        if (mosaicDepth(uv) >= 1.0e5 || (material >= 0.0 && m != floor(material + 0.5))) return color;
        float3 n = mosaicNormal(uv), v = mosaicViewDir(uv);
        float3 r = reflect(v, n), p = mosaicHitPos(uv) + n * bias;
        float t = mosaicTrace(p, r, maxDistance);
        float3 refl = mosaicSky(r);
        if (t >= 0.0) {
            float4 clip = mul(frame.projection, mul(frame.modelView, float4(p + r * t, 1.0)));
            float2 suv = clip.xy / clip.w * 0.5 + 0.5;
            refl = (clip.w > 0.0 && all(suv >= 0.0) && all(suv <= 1.0)) ? mosaicScene(suv).rgb : color.rgb * offscreen;
        }
        float fres = f0 + (1.0 - f0) * pow(1.0 - max(dot(-v, n), 0.0), 5.0);
        return float4(lerp(color.rgb, refl, clamp(amount + fres, 0.0, 1.0) * amount * 2.0), color.a);""", "godRays", """
        float3 L = mosaicSunDir();
        if (L.y <= 0.0) return color;
        float4 clip = mul(frame.projection, mul(frame.modelView, float4(L, 0.0)));
        if (clip.w <= 0.0) return color;
        float2 sun = clip.xy / clip.w * 0.5 + 0.5;
        int k = int(clamp(samples, 4.0, 64.0));
        float2 step = (sun - uv) / float(k);
        float2 q = uv;
        float w = 1.0, sum = 0.0;
        for (int i = 0; i < 64; i++) {
            if (i >= k) break;
            q += step;
            if (mosaicDepth(clamp(q, 0.0, 1.0)) >= 1.0e5) sum += w;
            w *= decay;
        }
        float edge = clamp(1.0 - length((sun - 0.5) * edgeFalloff), 0.0, 1.0);
        float3 light = shaftColor * sum / float(k) * strength * edge * clamp(L.y * sunHeightFade, 0.0, 1.0);
        return float4(color.rgb + light, color.a);"""); }

    // ---------------------------------------------------------------- post effects
    static {
        add("vec4 vignette(vec4 color, vec2 uv, float strength = 0.6, float radius = 0.75, float softness = 0.45, vec3 tint = vec3(0.0))", "Vignette",
            "Darkens (or tints) the corners of the screen: radius where it starts, softness of the edge.", """
            float v = smoothstep(radius, radius - softness, length(uv - 0.5) * 1.4142);
            return vec4(mix(mix(color.rgb, tint, strength), color.rgb, v), color.a);""");
        add("vec4 colorGrade(vec4 color, float exposure = 0, float contrast = 1, float saturation = 1, vec3 tint = 1, float pivot = 0.18, vec3 lift = vec3(0.0), vec3 gain = vec3(1.0))",
            "Color grade", "Exposure (stops), contrast around a pivot grey, saturation, tint, lift (shadows) and gain (highlights).", """
            vec3 c = color.rgb * exp2(exposure) * tint;
            c = (c - pivot) * contrast + pivot;
            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(vec3(l), c, saturation);
            c = c * gain + lift * (1.0 - c);
            return vec4(max(c, 0.0), color.a);""");
        add("vec4 filmGrain(vec4 color, vec2 uv, float amount = 0.05, float speed = 61, float size = 1, float colored = 0)", "Film grain",
            "Animated noise like film grain: amount, how fast it changes, grain size in pixels, colour grain (0 grey .. 1 colour).", """
            vec2 p = floor(uv * frame.viewSize.xy / max(size, 1.0)) + frame.timing.x * speed;
            float n = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            vec3 nc = vec3(n, fract(n * 7.13), fract(n * 13.7));
            return vec4(color.rgb + (mix(vec3(n), nc, colored) - 0.5) * amount, color.a);""");
        add("vec4 chromaticAberration(vec2 uv, float amount = 0.004, vec2 center = vec2(0.5))", "Chromatic aberration",
            "Splits red and blue away from a centre, like a cheap lens.", """
            vec2 d = (uv - center) * amount * 2.0;
            return vec4(mosaicScene(uv + d).r, mosaicScene(uv).g, mosaicScene(uv - d).b, 1.0);""");
        add("vec4 sharpen(vec2 uv, float amount = 0.5, float radius = 1)", "Sharpen",
            "Boosts edges with a 5-tap unsharp mask of the world image; radius in pixels.", """
            vec2 px = frame.viewSize.zw * radius;
            vec4 c = mosaicScene(uv);
            vec4 n = mosaicScene(uv + vec2(px.x, 0.0)) + mosaicScene(uv - vec2(px.x, 0.0))
                   + mosaicScene(uv + vec2(0.0, px.y)) + mosaicScene(uv - vec2(0.0, px.y));
            return vec4(max(c.rgb + (c.rgb * 4.0 - n.rgb) * amount, 0.0), c.a);""");
        add("vec4 depthFog(vec4 color, vec2 uv, vec3 fogColor = vec3(0.62, 0.72, 0.85), float start = 16, float end = 160, float density = 1, float skyAmount = 0)",
            "Distance fog", "Fades distant blocks into a fog colour between start and end (blocks); density scales it, skyAmount fogs the sky too.", """
            float d = mosaicDepth(uv);
            float f = d >= 1.0e5 ? skyAmount : clamp(smoothstep(start, end, d) * density, 0.0, 1.0);
            return vec4(mix(color.rgb, fogColor, f), color.a);""");
        add("vec4 posterize(vec4 color, float steps = 6)", "Posterize",
            "Reduces colours to a few bands (comic / toon look).", """
            return vec4(floor(color.rgb * steps + 0.5) / steps, color.a);""");
        add("vec4 scanlines(vec4 color, vec2 uv, float strength = 0.25, float density = 1, float speed = 0)", "Scanlines",
            "Horizontal CRT lines: density per pixel row, speed scrolls them.", """
            float s = 0.5 + 0.5 * sin((uv.y * frame.viewSize.y + frame.timing.x * speed) * 3.14159 * density);
            return vec4(color.rgb * (1.0 - strength * s), color.a);""");
        add("vec4 depthOutline(vec4 color, vec2 uv, vec3 lineColor = vec3(0.0), float threshold = 0.08, float width = 1, float maxDistance = 1000)", "Outline",
            "Draws lines where the distance jumps (block silhouettes): threshold relative to distance, width in pixels.", """
            vec2 px = frame.viewSize.zw * width;
            float d = min(mosaicDepth(uv), maxDistance);
            float dx = abs(min(mosaicDepth(uv + vec2(px.x, 0.0)), maxDistance) - d) + abs(min(mosaicDepth(uv - vec2(px.x, 0.0)), maxDistance) - d);
            float dy = abs(min(mosaicDepth(uv + vec2(0.0, px.y)), maxDistance) - d) + abs(min(mosaicDepth(uv - vec2(0.0, px.y)), maxDistance) - d);
            float edge = step(threshold * max(d, 1.0), dx + dy);
            return vec4(mix(color.rgb, lineColor, edge), color.a);""");
        add("vec4 glow(vec4 color, vec2 uv, float threshold = 1, float intensity = 0.4, float radius = 6, vec3 tint = vec3(1.0))", "Glow",
            "Cheap bloom: bright areas above the threshold bleed light around them (8 taps, radius in pixels).", """
            vec2 px = frame.viewSize.zw * radius;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398;
                acc += max(mosaicScene(uv + vec2(cos(a), sin(a)) * px).rgb - threshold, 0.0);
            }
            return vec4(color.rgb + acc / 8.0 * intensity * tint, color.a);""");
        add("vec4 heatHaze(vec2 uv, float amount = 0.002, float speed = 2, float frequency = 40)", "Heat haze",
            "Wobbles the image like hot air: amount, speed and how dense the ripples are.", """
            float t = frame.timing.x * speed;
            vec2 o = vec2(sin(uv.y * frequency + t), cos(uv.x * frequency * 0.875 + t * 1.3)) * amount;
            return mosaicScene(uv + o);""");
        add("vec4 skyShadow(vec4 color, vec2 uv, float darkness = 0.45, vec3 skyDirection = vec3(0.32, 0.90, 0.28), float bias = 0.01)",
            "Sky shadow (ray traced)", "Traces one ray per pixel toward the sky direction and darkens what is blocked.", """
            float t = mosaicDepth(uv);
            if (t >= 1.0e5) return color;
            vec3 p = mosaicEye() + mosaicViewDir(uv) * (t - bias);
            float lit = mosaicShadow(p, normalize(skyDirection));
            return vec4(color.rgb * mix(1.0 - darkness, 1.0, lit), color.a);""");
    }

    // ---------------------------------------------------------------- water
    static {
        add("vec4 waterColor(vec4 color, vec2 uv, vec3 deepColor = vec3(0.02, 0.16, 0.22), float clarity = 3, float tint = 0.6, vec3 shallowColor = vec3(0.55, 0.85, 0.95))",
            "Water color", "Absorbs light by how much water the view crosses: shallow water keeps the shallow tint, deep water"
                + " turns to the deep colour; clarity in blocks.", """
            float d = mosaicWaterDepth(uv);
            if (d <= 0.0) return color;
            float keep = exp(-d / max(clarity, 0.01));
            vec3 absorbed = color.rgb * mix(vec3(1.0), shallowColor, tint) * keep;
            return vec4(absorbed + deepColor * (1.0 - keep), color.a);""");
        add("vec4 underwater(vec4 color, vec2 uv, vec3 fogColor = vec3(0.04, 0.22, 0.32), float density = 0.09, float wobble = 0.003, vec3 tint = vec3(0.6, 0.9, 1.0), float speed = 2, float maxDistance = 200)",
            "Underwater", "While the camera is in water: wobble, a tint and fog by distance.", """
            if (mosaicUnderwater() < 0.5) return color;
            float t = frame.timing.x * speed * 0.5;
            vec2 w = uv + vec2(sin(uv.y * 30.0 + t * 2.0), cos(uv.x * 25.0 + t * 1.7)) * wobble;
            vec4 c = mosaicScene(w);
            float f = 1.0 - exp(-min(mosaicDepth(w), maxDistance) * density);
            return vec4(mix(c.rgb * tint, fogColor, f), c.a);""");
        add("vec4 caustics(vec4 color, vec2 uv, float strength = 0.4, float scale = 1, float speed = 0.8, float sharpness = 4, float waterAbove = 0.6)",
            "Water caustics", "Animated light ripples on blocks that lie under water: scale, speed, how sharp the lines are,"
                + " how far above the surface water is looked for (blocks).", """
            float t = mosaicDepth(uv);
            if (t >= 1.0e5) return color;
            vec3 rel = mosaicEye() + mosaicViewDir(uv) * t;
            if (mosaicMaterialAt(rel + vec3(0.0, waterAbove, 0.0)) != 1.0) return color;
            vec2 p = (frame.cameraPosition.xz + rel.xz) * 1.7 * scale;
            float tm = frame.timing.x * speed, c = 0.0;
            for (int i = 0; i < 3; i++) {
                p = p * 1.31 + vec2(sin(p.y + tm), cos(p.x - tm));
                c += abs(sin(p.x) * cos(p.y));
            }
            c = pow(clamp(1.0 - c / 3.0, 0.0, 1.0), sharpness);
            return vec4(color.rgb * (1.0 + c * strength * 4.0), color.a);""");
        add("vec4 waterSparkle(vec4 color, vec2 uv, float strength = 0.6, float density = 0.015, float size = 6, float speed = 4, vec3 tint = vec3(1.0))",
            "Water sparkle", "Twinkling glints on the water surface: how many (density), their size per block, how fast they change.", """
            if (mosaicWaterDepth(uv) <= 0.0 || mosaicUnderwater() > 0.5) return color;
            vec3 w = mosaicWorldPos(uv);
            float n = fract(sin(dot(floor(w.xz * size) + floor(frame.timing.x * speed), vec2(12.9898, 78.233))) * 43758.5453);
            return vec4(color.rgb + step(1.0 - density, n) * strength * tint, color.a);""");
    }

    // ---------------------------------------------------------------- world looks
    static {
        add("vec4 caveDarkness(vec4 color, vec2 uv, float strength = 0.6, float torchProtect = 1)", "Cave darkness",
            "Darkens surfaces the sky cannot reach; torchProtect = how much block light keeps them lit.", """
            float sky = mosaicHitSkyLight(uv), lamp = mosaicHitBlockLight(uv);
            float dark = (1.0 - sky) * (1.0 - lamp * torchProtect);
            return vec4(color.rgb * (1.0 - strength * dark), color.a);""");
        add("vec4 torchWarmth(vec4 color, vec2 uv, vec3 warmColor = vec3(1.0, 0.62, 0.3), float strength = 0.5, float boost = 1.4, float falloff = 2)",
            "Torch warmth", "Warms surfaces lit by torches, lamps and lava: colour, strength, extra brightness, falloff power of the light level.", """
            float lamp = pow(mosaicHitBlockLight(uv), falloff);
            return vec4(color.rgb * mix(vec3(1.0), warmColor * boost, lamp * strength), color.a);""");
        add("vec4 leafGlow(vec4 color, vec2 uv, vec3 glowColor = vec3(0.55, 0.85, 0.2), float strength = 0.35, float focus = 3, float material = 5)",
            "Leaf glow", "Leaves (or another material) let sunlight through when you look toward the sun: focus narrows the glow.", """
            if (mosaicHitMaterial(uv) != floor(material + 0.5)) return color;
            float toward = max(dot(mosaicViewDir(uv), mosaicSunDir()), 0.0);
            return vec4(color.rgb + glowColor * pow(toward, focus) * strength * mosaicHitSkyLight(uv), color.a);""");
        add("vec4 lavaHeat(vec2 uv, float amount = 0.004, float reach = 6, float speed = 3, float frequency = 60)", "Lava heat",
            "Hot air wobble above and around lava: amount, reach in blocks, speed, ripple density.", """
            float lava = mosaicLavaDepth(uv) > 0.0 || mosaicHitMaterial(uv) == 2.0 ? 1.0 : mosaicHitBlockLight(uv);
            float t = frame.timing.x * speed;
            vec2 o = vec2(sin(uv.y * frequency + t), cos(uv.x * frequency * 0.83 + t * 1.2)) * amount * lava * smoothstep(reach, 0.0, min(mosaicDepth(uv), reach));
            return mosaicScene(uv + o);""");
        add("vec4 snowSparkle(vec4 color, vec2 uv, float strength = 0.5, float density = 0.008, float size = 16, float speed = 2)", "Snow sparkle",
            "Tiny glints on snow and ice: how many, their size per block, how fast they change.", """
            float m = mosaicHitMaterial(uv);
            if (m != 9.0 && m != 10.0) return color;
            vec3 w = mosaicWorldPos(uv);
            float n = fract(sin(dot(floor(w.xz * size + w.y * 7.0), vec2(12.9898, 78.233)) + floor(frame.timing.x * speed)) * 43758.5453);
            return vec4(color.rgb + step(1.0 - density, n) * strength, color.a);""");
        add("vec4 materialTint(vec4 color, vec2 uv, float material = 5, vec3 tint = vec3(1.15, 1.0, 0.85), float amount = 1)",
            "Material tint", "Recolours one material class everywhere: 3 stone, 4 dirt, 5 leaves, 6 plants, 7 wood, 8 sand,"
                + " 9 snow, 10 ice, 11 glass, 12 metal, 13 lights.", """
            if (mosaicHitMaterial(uv) != floor(material + 0.5)) return color;
            return vec4(mix(color.rgb, color.rgb * tint, amount), color.a);""");
    }

    // ---------------------------------------------------------------- rendering: voxels, rays, paths
    static {
        add("Rendering", "vec4 voxelRender(vec2 uv, float shadows = 1, float maxDistance = 96, float shadowDistance = 64, vec3 sunColor = vec3(1.0, 0.95, 0.85), float skyAmbient = 0.35, vec3 torchColor = vec3(1.0, 0.7, 0.4), float torchStrength = 1.2, float glowStrength = 1.5, float surfaceOffset = 0.5)",
            "Voxel render", "Renders the world volume by itself: block colours, sun with voxel shadows, sky and torch light."
                + " Works without hardware ray tracing.", """
            vec3 o = mosaicEye(), d = mosaicViewDir(uv), n, n2;
            float m, m2;
            float t = mosaicVoxelTrace(o, d, maxDistance, n, m);
            if (t < 0.0) return vec4(mosaicSky(d), 1.0);
            vec3 p = o + d * t;
            vec3 albedo = mosaicAlbedo(m);
            float sky = mosaicSkyLightAt(p + n * surfaceOffset), lamp = mosaicBlockLightAt(p + n * surfaceOffset);
            vec3 L = mosaicSunDir();
            float ndl = max(dot(n, L), 0.0);
            float vis = (shadows > 0.5 && ndl > 0.0) ? (mosaicVoxelTrace(p + n * 0.01, L, shadowDistance, n2, m2) < 0.0 ? 1.0 : 0.0) : 1.0;
            vec3 light = sunColor * ndl * vis * sky + mosaicSky(n) * skyAmbient * sky + torchColor * lamp * lamp * torchStrength;
            vec3 c = albedo * light;
            if (m == 2.0 || m == 13.0) c += albedo * glowStrength;
            return vec4(c, 1.0);""");
        add("Rendering", "vec4 voxelSunShadow(vec4 color, vec2 uv, float darkness = 0.55, float maxDistance = 64, float farShadowFrom = 40, float farShadowTo = 192, float bias = 0.03, float biasPerBlock = 0.004, float selfHit = 0.35, float moonStrength = 0.35, float horizonFade = 0.15, float sunAngularRadius = 0.02)",
            "Sun shadows (voxels)", "Shadows of the sun (the moon at night) through the world volume up to maxDistance, then the height field"
                + " from farShadowFrom to farShadowTo blocks for far hills and trees; bias pushes the start off the surface (more far away),"
                + " hits closer than selfHit are the pixel's own block. Works with every renderer; caves keep their own darkness.", """
            float t = mosaicDepth(uv);
            if (t >= 1.0e5) return color;
            vec3 n = mosaicNormal(uv), n2;
            float m2;
            vec3 L = mosaicSunDir();
            float strength = L.y > 0.0 ? smoothstep(0.0, horizonFade, L.y) : moonStrength * smoothstep(0.0, horizonFade, -L.y);
            if (L.y < 0.0) L = -L;
            if (sunAngularRadius > 0.0) { // soft shadows: a point of the sun's disc, a new one every frame (TAA / Accumulate average them)
                vec3 jh = mosaicHash33(vec3(uv * frame.viewSize.xy, 0.0) + vec3(frame.timing.z * 0.6180339, frame.timing.z * 0.4142135, 0.0));
                vec3 jt = normalize(abs(L.y) < 0.99 ? cross(L, vec3(0.0, 1.0, 0.0)) : cross(L, vec3(1.0, 0.0, 0.0)));
                vec3 jb = cross(L, jt);
                float ja = jh.y * 6.2831853, jr = sqrt(jh.x) * sunAngularRadius;
                L = normalize(L + (jt * cos(ja) + jb * sin(ja)) * jr);
            }
            float lit = 0.0;
            if (dot(n, L) > 0.0) {
                vec3 p = mosaicEye() + mosaicViewDir(uv) * t + n * (bias + t * biasPerBlock);
                float h = mosaicVoxelTrace(p, L, maxDistance, n2, m2);
                lit = h >= selfHit ? 0.0 : mosaicFarShadow(p, L, t > farShadowFrom ? 1.5 : farShadowFrom, farShadowTo);
            }
            float k = darkness * strength * clamp(mosaicHitSkyLight(uv), 0.0, 1.0);
            return vec4(color.rgb * mix(1.0 - k, 1.0, lit), color.a);""");
        add("Rendering", "vec4 ssao(vec4 color, vec2 uv, float radius = 1.2, float strength = 0.7, float samples = 10, float maxDarkening = 0.85, float angleBias = 0.05, float spread = 0.6)",
            "Ambient occlusion (screen)", "Darkens corners and contact areas from the depth guide alone (ray tracing or"
                + " raster depth): samples around each pixel's surface; radius in blocks, at most maxDarkening.", """
            float t = mosaicDepth(uv);
            if (t >= 1.0e5) return color;
            vec3 p = mosaicHitPos(uv), n = mosaicNormal(uv);
            // interleaved gradient noise, shifted every frame: an even pattern Temporal smoothing resolves cleanly
            vec2 px = uv * frame.viewSize.xy + vec2(5.588238, 5.588238) * mod(frame.timing.z, 64.0);
            float n0 = fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715)))) * 6.2831853;
            float occ = 0.0, count = 0.0;
            int k = int(clamp(samples, 1.0, 32.0));
            for (int i = 0; i < 32; i++) {
                if (i >= k) break;
                float a = n0 + float(i) * 2.3999632, r = (float(i) + 0.5) / float(k);
                vec2 o = vec2(cos(a), sin(a)) * r * radius / max(t, 0.5);
                vec2 su = uv + o * vec2(frame.viewSize.y / frame.viewSize.x, 1.0) * spread;
                if (su.x < 0.0 || su.y < 0.0 || su.x > 1.0 || su.y > 1.0) continue;
                vec3 d = mosaicHitPos(su) - p;
                float l = length(d);
                if (l < 1e-3) continue;
                occ += max(dot(n, d / l) - angleBias, 0.0) * clamp(1.0 - l / (radius * 2.0), 0.0, 1.0);
                count += 1.0;
            }
            float ao = 1.0 - clamp(occ / max(count, 1.0) * 2.0 * strength, 0.0, maxDarkening);
            return vec4(color.rgb * ao, color.a);""");
        add("Rendering", "vec4 godRays(vec4 color, vec2 uv, float strength = 0.3, float decay = 0.96, float samples = 32, vec3 shaftColor = vec3(1.0, 0.86, 0.65), float edgeFalloff = 1.2, float sunHeightFade = 3)",
            "Light shafts (screen)", "Sun shafts streaming from the sun across the screen where the sky shows through"
                + " leaves and edges; fade toward the screen edge (edgeFalloff) and with a low sun (sunHeightFade).", """
            vec3 L = mosaicSunDir();
            if (L.y <= 0.0) return color;
            vec4 clip = frame.projection * (frame.modelView * vec4(L, 0.0));
            if (clip.w <= 0.0) return color;
            vec2 sun = clip.xy / clip.w * 0.5 + 0.5;
            int k = int(clamp(samples, 4.0, 64.0));
            vec2 step = (sun - uv) / float(k);
            vec2 q = uv;
            float w = 1.0, sum = 0.0;
            for (int i = 0; i < 64; i++) {
                if (i >= k) break;
                q += step;
                if (mosaicDepth(clamp(q, 0.0, 1.0)) >= 1.0e5) sum += w;
                w *= decay;
            }
            float edge = clamp(1.0 - length((sun - 0.5) * edgeFalloff), 0.0, 1.0);
            vec3 light = shaftColor * sum / float(k) * strength * edge * clamp(L.y * sunHeightFade, 0.0, 1.0);
            return vec4(color.rgb + light, color.a);""");
        add("Rendering", "vec4 pathTraceGI(vec4 color, vec2 uv, float samples = 2, float bounces = 2, float strength = 1, float maxDistance = 32, float shadowDistance = 48, vec3 sunColor = vec3(1.0, 0.95, 0.85), vec3 torchColor = vec3(1.0, 0.7, 0.4), float glowStrength = 2, float skyStrength = 1)",
            "Path-traced GI", "Bounces light through the world volume (voxel paths with sun, sky and torches) and adds"
                + " it to the image. Noisy: more samples cost more.", """
            float t0 = mosaicDepth(uv);
            if (t0 >= 1.0e5) return color;
            vec3 n0 = mosaicNormal(uv);
            vec3 p0 = mosaicHitPos(uv) + n0 * 0.02;
            uint seed = uint(uv.x * frame.viewSize.x) * 1973u + uint(uv.y * frame.viewSize.y) * 9277u + uint(frame.timing.z) * 26699u;
            vec3 L = mosaicSunDir(), irradiance = vec3(0.0);
            int ns = int(clamp(samples, 1.0, 16.0)), nb = int(clamp(bounces, 1.0, 4.0));
            for (int s = 0; s < ns; s++) {
                vec3 pos = p0, nrm = n0, throughput = vec3(1.0);
                for (int b = 0; b < nb; b++) {
                    seed = seed * 747796405u + 2891336453u;
                    float r1 = float((seed >> 8) & 0xFFFFFFu) / 16777216.0;
                    seed = seed * 747796405u + 2891336453u;
                    float r2 = float((seed >> 8) & 0xFFFFFFu) / 16777216.0;
                    vec3 tg = normalize(abs(nrm.y) < 0.99 ? cross(nrm, vec3(0.0, 1.0, 0.0)) : cross(nrm, vec3(1.0, 0.0, 0.0)));
                    vec3 bt = cross(nrm, tg);
                    float phi = 6.2831853 * r1, cr = sqrt(r2);
                    vec3 dir = normalize(tg * cos(phi) * cr + bt * sin(phi) * cr + nrm * sqrt(1.0 - r2));
                    vec3 hn, sn;
                    float hm, sm;
                    float tt = mosaicVoxelTrace(pos, dir, maxDistance, hn, hm);
                    if (tt < 0.0) { irradiance += throughput * mosaicSky(dir) * skyStrength; break; }
                    vec3 hp = pos + dir * tt;
                    vec3 alb = mosaicAlbedo(hm);
                    float lamp = mosaicBlockLightAt(hp + hn * 0.5), sky = mosaicSkyLightAt(hp + hn * 0.5);
                    float ndl = max(dot(hn, L), 0.0);
                    float vis = ndl > 0.0 && mosaicVoxelTrace(hp + hn * 0.01, L, shadowDistance, sn, sm) < 0.0 ? 1.0 : 0.0;
                    vec3 emit = (hm == 2.0 || hm == 13.0) ? alb * glowStrength : vec3(0.0);
                    irradiance += throughput * alb * (sunColor * ndl * vis * sky + torchColor * lamp * lamp) + throughput * emit;
                    throughput *= alb;
                    pos = hp + hn * 0.01;
                    nrm = hn;
                }
            }
            irradiance /= float(ns);
            return vec4(color.rgb + color.rgb * irradiance * strength, color.a);""");
        add("Rendering", "vec4 rtAmbientOcclusion(vec4 color, vec2 uv, float radius = 1.5, float samples = 4, float strength = 0.8, float bias = 0.02)",
            "RT ambient occlusion", "Hardware rays around each surface darken corners and crevices: radius in blocks.", """
            if (mosaicDepth(uv) >= 1.0e5) return color;
            vec3 n = mosaicNormal(uv), p = mosaicHitPos(uv) + n * bias;
            vec3 tg = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
            vec3 bt = cross(n, tg);
            int ns = int(clamp(samples, 1.0, 16.0));
            float occluded = 0.0, angle = fract(sin(dot(uv * frame.viewSize.xy, vec2(12.9898, 78.233))) * 43758.5453) * 6.2831853;
            for (int i = 0; i < ns; i++) {
                float a = angle + float(i) * 2.399963, r = sqrt((float(i) + 0.5) / float(ns));
                vec3 dir = normalize(tg * cos(a) * r + bt * sin(a) * r + n * sqrt(1.0 - r * r));
                if (mosaicTrace(p, dir, radius) >= 0.0) occluded += 1.0;
            }
            return vec4(color.rgb * (1.0 - strength * occluded / float(ns)), color.a);""");
        add("Rendering", "vec4 rtReflections(vec4 color, vec2 uv, float amount = 0.35, float material = 1, float maxDistance = 128, float f0 = 0.02, float bias = 0.02, float offscreen = 0.5)", "RT reflections",
            "Hardware-traced mirror reflections on one material class (1 water by default; -1 = every surface); offscreen = how dark a"
                + " reflection of something outside the screen is.", """
            float m = mosaicHitMaterial(uv);
            if (mosaicDepth(uv) >= 1.0e5 || (material >= 0.0 && m != floor(material + 0.5))) return color;
            vec3 n = mosaicNormal(uv), v = mosaicViewDir(uv);
            vec3 r = reflect(v, n), p = mosaicHitPos(uv) + n * bias;
            float t = mosaicTrace(p, r, maxDistance);
            vec3 refl = mosaicSky(r);
            if (t >= 0.0) {
                vec4 clip = frame.projection * (frame.modelView * vec4(p + r * t, 1.0));
                vec2 suv = clip.xy / clip.w * 0.5 + 0.5;
                refl = (clip.w > 0.0 && all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
                    ? mosaicScene(suv).rgb : color.rgb * offscreen;
            }
            float fres = f0 + (1.0 - f0) * pow(1.0 - max(dot(-v, n), 0.0), 5.0);
            return vec4(mix(color.rgb, refl, clamp(amount + fres, 0.0, 1.0) * amount * 2.0), color.a);""");
        // shading building blocks
        add("Shading", "float fresnelSchlick(float cosTheta, float f0 = 0.04, float power = 5)", "Fresnel (Schlick)",
            "How reflective a surface is at a view angle (cosTheta = dot(normal, toViewer)).", """
            return f0 + (1.0 - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), power);""");
        add("Shading", "float ggxSpecular(vec3 n, vec3 v, vec3 l, float roughness = 0.5)", "GGX specular",
            "Microfacet highlight strength (n normal, v to viewer, l to light).", """
            vec3 h = normalize(v + l);
            float a = max(roughness * roughness, 1e-3), a2 = a * a;
            float nh = max(dot(n, h), 0.0), nv = max(dot(n, v), 1e-4), nl = max(dot(n, l), 0.0);
            float d = a2 / (3.14159265 * pow(nh * nh * (a2 - 1.0) + 1.0, 2.0));
            float k = a * 0.5;
            float g = nv / (nv * (1.0 - k) + k) * nl / (nl * (1.0 - k) + k);
            return d * g / (4.0 * nv + 1e-4);""");
        add("Shading", "float lambert(vec3 n, vec3 l, float wrap = 0)", "Lambert diffuse",
            "Diffuse light: dot(normal, toLight), never negative; wrap > 0 lets light reach past the terminator (foliage, cloth).", """
            return max((dot(normalize(n), normalize(l)) + wrap) / (1.0 + wrap), 0.0);""");
        add("Shading", "vec3 acesTonemap(vec3 c, float exposure = 0)", "ACES tonemap", "Filmic curve from HDR to display range (exposure in stops).", """
            c *= exp2(exposure);
            return clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);""");
        add("Shading", "vec3 skyColor(vec3 dir)", "Sky color", "The analytic sky in a direction (follows the time of day).", """
            return mosaicSky(normalize(dir));""");
        add("Shading", "vec3 materialColor(float material)", "Material color", "Typical colour of a material class.", """
            return mosaicAlbedo(material);""");
        add("Shading", "float sunVisibility(vec2 uv, float maxDistance = 64, float farShadowFrom = 40, float farShadowTo = 192, float bias = 0.03, float biasPerBlock = 0.004, float selfHit = 0.35, float sunAngularRadius = 0.02)",
            "Sun visibility (voxels)", "1 where the sun (the moon at night) reaches the pixel's surface, 0 in shadow: a ray through the world"
                + " volume up to maxDistance, then the height field from farShadowFrom to farShadowTo; works without hardware ray tracing.", """
            float t = mosaicDepth(uv);
            if (t >= 1.0e5) return 1.0;
            vec3 n = mosaicNormal(uv), n2;
            float m2;
            vec3 L = mosaicSunDir();
            if (L.y < 0.0) L = -L;
            if (dot(n, L) <= 0.0) return 0.0;
            if (sunAngularRadius > 0.0) { // soft shadows: a point of the sun's disc, a new one every frame (TAA / Accumulate average them)
                vec3 jh = mosaicHash33(vec3(uv * frame.viewSize.xy, 0.0) + vec3(frame.timing.z * 0.6180339, frame.timing.z * 0.4142135, 0.0));
                vec3 jt = normalize(abs(L.y) < 0.99 ? cross(L, vec3(0.0, 1.0, 0.0)) : cross(L, vec3(1.0, 0.0, 0.0)));
                vec3 jb = cross(L, jt);
                float ja = jh.y * 6.2831853, jr = sqrt(jh.x) * sunAngularRadius;
                L = normalize(L + (jt * cos(ja) + jb * sin(ja)) * jr);
            }
            // positions rebuilt from depth drift with distance: the start moves off the surface by more far away, and a
            // hit right at the start is the pixel's own block, not a caster
            vec3 p = mosaicEye() + mosaicViewDir(uv) * t + n * (bias + t * biasPerBlock);
            float h = mosaicVoxelTrace(p, L, maxDistance, n2, m2);
            if (h >= selfHit) return 0.0;
            return mosaicFarShadow(p, L, t > farShadowFrom ? 1.5 : farShadowFrom, farShadowTo);""");
        add("Shading", "float voxelDistance(vec3 origin, vec3 dir, float maxDistance = 96)", "Voxel distance",
            "Distance along a ray to the first solid block of the world volume (-1 = none).", """
            vec3 n;
            float m;
            return mosaicVoxelTrace(origin, normalize(dir), maxDistance, n, m);""");
    }

    // ---------------------------------------------------------------- render pipeline: the pieces of a renderer of your own
    // Each takes surface data (position, normal, light levels from the G-buffer nodes) and gives light in linear HDR, so a
    // renderer is wired as: G-buffer -> lights (+ GI into a Buffer, Accumulate, Denoise) -> Combine -> Tone map -> TAA.
    // Every number that changes the picture is a parameter: nothing is decided inside a node.
    static {
        add("Render pipeline", "vec3 sceneAlbedo(vec2 uv, float brightness = 1, float gamma = 2.2, float lightCurve = 1.4, float minLight = 0.05, float ambientFloor = 0.08, float nightLevel = 0.2)",
            "Albedo (from the game image)", "The surface colour without the game's own lighting, in linear light: the game image (gamma)"
                + " divided by an estimate of its light map (sky light by time of day, block light; lightCurve, the darkest light"
                + " minLight, a floor ambientFloor, night sky level nightLevel). The base colour of a renderer of your own.", """
            vec3 c = pow(max(mosaicScene(uv).rgb, vec3(0.0)), vec3(gamma));
            if (mosaicDepth(uv) >= 1.0e5) return c;
            float sky = mosaicHitSkyLight(uv), lamp = mosaicHitBlockLight(uv);
            float day = clamp(mosaicSunDir().y * 4.0 + 0.35, nightLevel, 1.0);
            float lm = max(max(sky * day, lamp), minLight);
            lm = pow(lm, lightCurve) * (1.0 - ambientFloor) + ambientFloor;
            return clamp(c / lm, vec3(0.0), vec3(1.0)) * brightness;""");
        add("Render pipeline", "vec3 sunLight(vec3 position, vec3 normal, float shadows = 1, float intensity = 2.2, vec3 sunColor = vec3(1.0, 0.95, 0.88), vec3 sunsetColor = vec3(1.0, 0.45, 0.18), float sunsetHeight = 0.35, vec3 moonColor = vec3(0.32, 0.4, 0.62), float moonLevel = 0.06, float horizonFade = 0.08, float shadowDistance = 64, float farShadowFrom = 40, float farShadowTo = 192, float bias = 0.03, float biasPerBlock = 0.004, float selfHit = 0.35, float visibility = -1, float sunAngularRadius = 0.02)",
            "Sun & moon light", "Direct light of the sun (the moon at night) on a surface: colours by day, at sunset (below sunsetHeight)"
                + " and at night (moonColor x moonLevel), fading at the horizon; shadows through the world volume up to shadowDistance,"
                + " then the height field from farShadowFrom to farShadowTo blocks; bias, biasPerBlock and selfHit keep surfaces from"
                + " shadowing themselves; wire a Sun shadow node into visibility (0..1) to share one shadow ray with Sun specular."
                + " position and normal from the G-buffer nodes.", """
            vec3 L = mosaicSunDir();
            float moon = L.y < 0.0 ? 1.0 : 0.0;
            if (L.y < 0.0) L = -L;
            float ndl = max(dot(normal, L), 0.0);
            if (ndl <= 0.0 || length(position) > 4000.0) return vec3(0.0);
            float vis = visibility >= 0.0 ? visibility : 1.0;
            if (visibility < 0.0 && shadows > 0.5) {
                vec3 n2;
                float m2;
                float d = length(position);
                vec3 p = position + normal * (bias + d * biasPerBlock);
                vec3 L0 = L;
            if (sunAngularRadius > 0.0) { // soft shadows: a point of the sun's disc, a new one every frame (TAA / Accumulate average them)
                vec3 jh = mosaicHash33(position * 7.13 + vec3(frame.timing.z * 0.6180339, frame.timing.z * 0.4142135, 0.0));
                vec3 jt = normalize(abs(L.y) < 0.99 ? cross(L, vec3(0.0, 1.0, 0.0)) : cross(L, vec3(1.0, 0.0, 0.0)));
                vec3 jb = cross(L, jt);
                float ja = jh.y * 6.2831853, jr = sqrt(jh.x) * sunAngularRadius;
                L = normalize(L + (jt * cos(ja) + jb * sin(ja)) * jr);
            }
                float h = mosaicVoxelTrace(p, L, shadowDistance, n2, m2);
                vis = h >= selfHit ? 0.0 : mosaicFarShadow(p, L, d > farShadowFrom ? 1.5 : farShadowFrom, farShadowTo);
                L = L0;
            }
            vec3 col = mix(sunsetColor, sunColor, smoothstep(0.0, sunsetHeight, L.y));
            col = mix(col, moonColor * moonLevel, moon);
            return col * ndl * vis * intensity * smoothstep(0.0, horizonFade, L.y);""");
        add("Render pipeline", "float sunShadow(vec3 position, vec3 normal, float shadowDistance = 64, float farShadowFrom = 40, float farShadowTo = 192, float bias = 0.03, float biasPerBlock = 0.004, float selfHit = 0.35, float sunAngularRadius = 0.02)",
            "Sun shadow", "1 where the sun (the moon at night) reaches a surface, 0 in shadow: through the world volume up to shadowDistance,"
                + " then the height field from farShadowFrom to farShadowTo. Wire it into Sun & moon light and Sun specular (visibility)"
                + " so both use one shadow ray. sunAngularRadius (radians) softens the shadow edges like a real sun disc.", """
            vec3 L = mosaicSunDir();
            if (L.y < 0.0) L = -L;
            if (sunAngularRadius > 0.0) { // soft shadows: a point of the sun's disc, a new one every frame (TAA / Accumulate average them)
                vec3 jh = mosaicHash33(position * 7.13 + vec3(frame.timing.z * 0.6180339, frame.timing.z * 0.4142135, 0.0));
                vec3 jt = normalize(abs(L.y) < 0.99 ? cross(L, vec3(0.0, 1.0, 0.0)) : cross(L, vec3(1.0, 0.0, 0.0)));
                vec3 jb = cross(L, jt);
                float ja = jh.y * 6.2831853, jr = sqrt(jh.x) * sunAngularRadius;
                L = normalize(L + (jt * cos(ja) + jb * sin(ja)) * jr);
            }
            if (dot(normal, L) <= 0.0 || length(position) > 4000.0) return 0.0;
            vec3 n2;
            float m2;
            float d = length(position);
            vec3 p = position + normal * (bias + d * biasPerBlock);
            float h = mosaicVoxelTrace(p, L, shadowDistance, n2, m2);
            return h >= selfHit ? 0.0 : mosaicFarShadow(p, L, d > farShadowFrom ? 1.5 : farShadowFrom, farShadowTo);""");
        add("Render pipeline", "float materialRoughness(vec2 uv, float defaultRoughness = 0.75, float water = 0.04, float ice = 0.12, float glass = 0.05, float metal = 0.35, float snow = 0.6, float leaves = 0.55, float stone = 0.8)",
            "Roughness (by material)", "Surface roughness of what the pixel shows, per material class (0 mirror .. 1 matte): feeds Sun specular and reflections.", """
            float m = mosaicHitMaterial(uv);
            if (m == 1.0) return water;
            if (m == 10.0) return ice;
            if (m == 11.0) return glass;
            if (m == 12.0) return metal;
            if (m == 9.0) return snow;
            if (m == 5.0) return leaves;
            if (m == 3.0) return stone;
            return defaultRoughness;""");
        add("Render pipeline", "vec3 sunSpecular(vec3 position, vec3 normal, float roughness = 0.5, float visibility = 1, float f0 = 0.04, float intensity = 2.2, vec3 sunColor = vec3(1.0, 0.95, 0.88), vec3 sunsetColor = vec3(1.0, 0.45, 0.18), float sunsetHeight = 0.35, float horizonFade = 0.08, float maxHighlight = 16)",
            "Sun specular", "The sun's highlight on a surface (GGX microfacets with Schlick Fresnel): sharp on water and glass, broad on stone;"
                + " roughness from Roughness (by material), visibility from Sun shadow. Added to the light after the albedo (it is not tinted by it).", """
            vec3 L = mosaicSunDir();
            if (L.y <= 0.0 || length(position) > 4000.0) return vec3(0.0);
            vec3 v = normalize(mosaicEye() - position);
            vec3 h = normalize(v + L);
            float a = max(roughness * roughness, 1e-3), a2 = a * a;
            float nh = max(dot(normal, h), 0.0), nv = max(dot(normal, v), 1e-4), nl = max(dot(normal, L), 0.0);
            if (nl <= 0.0) return vec3(0.0);
            float dd = nh * nh * (a2 - 1.0) + 1.0;
            float D = a2 / (3.14159265 * dd * dd);
            float k = a * 0.5;
            float G = nv / (nv * (1.0 - k) + k) * nl / (nl * (1.0 - k) + k);
            float F = f0 + (1.0 - f0) * pow(1.0 - max(dot(h, v), 0.0), 5.0);
            float spec = min(D * G * F / (4.0 * nv * nl + 1e-4) * nl, maxHighlight);
            vec3 col = mix(sunsetColor, sunColor, smoothstep(0.0, sunsetHeight, L.y));
            return col * spec * visibility * intensity * smoothstep(0.0, horizonFade, L.y);""");
        add("Render pipeline", "vec4 atmosphere(vec4 color, vec2 uv, float density = 0.006, float start = 16, float heightFalloff = 0.03, float seaLevel = 63, vec3 tint = vec3(1.0), float sunScatter = 0.8, float scatterSharpness = 8, float skyAmount = 0, float maxFog = 1)",
            "Atmosphere", "Aerial perspective in linear light: distant blocks fade into the colour of the sky toward them, thicker near sea level"
                + " (heightFalloff per block above seaLevel) and glowing toward the sun (sunScatter, scatterSharpness). Put it before Tone map.", """
            float t = mosaicDepth(uv);
            vec3 d = mosaicViewDir(uv);
            if (t >= 1.0e5 && skyAmount <= 0.0) return color;
            float dist = t >= 1.0e5 ? 1000.0 : max(t - start, 0.0);
            float h = mosaicEye().y + frame.cameraPosition.y + d.y * min(t, 1000.0) * 0.5 - seaLevel;
            float thick = exp(-max(h, 0.0) * heightFalloff);
            float f = clamp((1.0 - exp(-dist * density * thick)) * (t >= 1.0e5 ? skyAmount : 1.0), 0.0, maxFog);
            vec3 L = mosaicSunDir();
            vec3 haze = mosaicSky(normalize(vec3(d.x, max(d.y, 0.02), d.z))) * tint
                + vec3(1.0, 0.85, 0.6) * pow(max(dot(d, L), 0.0), scatterSharpness) * sunScatter * clamp(L.y * 4.0, 0.0, 1.0);
            return vec4(mix(color.rgb, haze, f), color.a);""");
        add("Render pipeline", "vec3 proceduralSky(vec3 dir, vec3 zenithColor = vec3(0.22, 0.42, 0.85), vec3 horizonColor = vec3(0.68, 0.80, 0.96), float gradientPower = 0.6, vec3 sunsetColor = vec3(1.0, 0.45, 0.2), float sunsetHeight = 0.3, float sunsetGlow = 0.9, float sunsetBelow = 0.25, float sunsetSpread = 2.5, vec3 nightColor = vec3(0.01, 0.015, 0.04), float dayRise = 2.5, float dayOffset = 0.3, float groundDarkening = 0.4, vec3 sunColor = vec3(1.0, 0.92, 0.8), float sunSize = 0.0004, float sunBrightness = 20, float sunGlow = 0.6, float sunGlowSharpness = 32, vec3 moonColor = vec3(0.8, 0.85, 1.0), float moonSize = 0.0003, float starDensity = 0.002, float starScale = 400, float starBrightness = 0.8, float cloudCoverage = 0.5, float cloudHeight = 192, float cloudScale = 0.008, float cloudSoftness = 0.18, float cloudDetail = 4, float cloudOpacity = 0.9, vec3 cloudColor = vec3(1.0), vec3 cloudShadowColor = vec3(0.45, 0.5, 0.6), float cloudSelfShadow = 0.35, float nightCloudLevel = 0.05, vec2 windDirection = vec2(1.0, 0.3), float windSpeed = 0.01, float cloudChange = 0.02, float cloudFadeDistance = 6000)",
            "Sky (procedural)", "A sky of your own in linear HDR for any direction: day gradient (zenith, horizon), sunset glow toward the sun,"
                + " night, sun and moon discs, stars, and a cloud layer drifting with the wind (coverage, height, scale, softness, colours)."
                + " Use it for reflections and GI misses; Sky background draws it behind the world.", """

            vec3 L = mosaicSunDir();
            float sunUp = L.y;
            float day = clamp(sunUp * dayRise + dayOffset, 0.0, 1.0);
            float sunset = smoothstep(-sunsetBelow, 0.0, sunUp) * (1.0 - smoothstep(0.0, sunsetHeight, sunUp));
            float up = clamp(dir.y, 0.0, 1.0);
            vec3 dayCol = mix(horizonColor, zenithColor, pow(up, gradientPower));
            float towardSun = pow(max(dot(normalize(vec3(dir.x, 0.0, dir.z) + 1e-4), normalize(vec3(L.x, 0.0, L.z) + 1e-4)), 0.0), 2.0);
            vec3 c = dayCol * day + nightColor * (1.0 - day);
            // sunset glow: low in the sky, strongest toward the sun, lasting while the sun is just below the horizon
            c += sunsetColor * sunsetGlow * sunset * (0.25 + 0.75 * towardSun) * pow(1.0 - up, sunsetSpread);
            c *= mix(1.0, groundDarkening, clamp(-dir.y * 4.0, 0.0, 1.0));
            // sun and moon discs
            float sd = dot(dir, L), md = dot(dir, -L);
            c += sunColor * (smoothstep(1.0 - sunSize, 1.0 - sunSize * 0.7, sd) * sunBrightness + pow(max(sd, 0.0), sunGlowSharpness) * sunGlow) * step(0.0, sunUp + 0.05);
            c += moonColor * smoothstep(1.0 - moonSize, 1.0 - moonSize * 0.7, md) * (1.0 - day);
            // stars: a fixed pattern of the sky, at night
            vec3 sp = floor(dir * starScale);
            float star = step(1.0 - starDensity, fract(sin(dot(sp, vec3(12.9898, 78.233, 37.719))) * 43758.5453));
            c += vec3(star) * starBrightness * (1.0 - day) * step(0.0, dir.y);
            // clouds: a noise layer on a plane cloudHeight above the world, drifting with the wind
            if (cloudCoverage > 0.0 && dir.y > 0.01) {
                float camY = mosaicEye().y + frame.cameraPosition.y;
                float tc = (cloudHeight - camY) / dir.y;
                if (tc > 0.0) {
                    vec2 wp = (frame.cameraPosition.xz + mosaicEye().xz + dir.xz * tc) * cloudScale + windDirection * frame.timing.x * windSpeed;
                    float n = mosaicNoise(vec3(wp, frame.timing.x * cloudChange), cloudDetail, 0.55, 0.0);
                    float dens = smoothstep(1.0 - cloudCoverage, 1.0 - cloudCoverage + cloudSoftness, n);
                    dens *= clamp(1.0 - tc / cloudFadeDistance, 0.0, 1.0);
                    vec3 sunTint = mix(sunsetColor, vec3(1.0), smoothstep(0.0, sunsetHeight, sunUp));
                    vec3 cloud = mix(cloudShadowColor, cloudColor * sunTint, clamp(day + 0.1, 0.0, 1.0) * (1.0 - n * cloudSelfShadow))
                        * max(day, nightCloudLevel);
                    c = mix(c, cloud, dens * cloudOpacity);
                }
            }
            return c;""");
        add("Render pipeline", "vec4 skyBackground(vec4 color, vec2 uv, vec3 zenithColor = vec3(0.22, 0.42, 0.85), vec3 horizonColor = vec3(0.68, 0.80, 0.96), float gradientPower = 0.6, vec3 sunsetColor = vec3(1.0, 0.45, 0.2), float sunsetHeight = 0.3, float sunsetGlow = 0.9, float sunsetBelow = 0.25, float sunsetSpread = 2.5, vec3 nightColor = vec3(0.01, 0.015, 0.04), float dayRise = 2.5, float dayOffset = 0.3, float groundDarkening = 0.4, vec3 sunColor = vec3(1.0, 0.92, 0.8), float sunSize = 0.0004, float sunBrightness = 20, float sunGlow = 0.6, float sunGlowSharpness = 32, vec3 moonColor = vec3(0.8, 0.85, 1.0), float moonSize = 0.0003, float starDensity = 0.002, float starScale = 400, float starBrightness = 0.8, float cloudCoverage = 0.5, float cloudHeight = 192, float cloudScale = 0.008, float cloudSoftness = 0.18, float cloudDetail = 4, float cloudOpacity = 0.9, vec3 cloudColor = vec3(1.0), vec3 cloudShadowColor = vec3(0.45, 0.5, 0.6), float cloudSelfShadow = 0.35, float nightCloudLevel = 0.05, vec2 windDirection = vec2(1.0, 0.3), float windSpeed = 0.01, float cloudChange = 0.02, float cloudFadeDistance = 6000)",
            "Sky background", "Replaces the pixels that see the sky with the procedural sky (the same inputs as Sky (procedural));"
                + " world pixels pass through. Put it after Combine, before Atmosphere and Tone map.", """
            if (mosaicDepth(uv) < 1.0e5) return color;
            vec3 dir = mosaicViewDir(uv);

            vec3 L = mosaicSunDir();
            float sunUp = L.y;
            float day = clamp(sunUp * dayRise + dayOffset, 0.0, 1.0);
            float sunset = smoothstep(-sunsetBelow, 0.0, sunUp) * (1.0 - smoothstep(0.0, sunsetHeight, sunUp));
            float up = clamp(dir.y, 0.0, 1.0);
            vec3 dayCol = mix(horizonColor, zenithColor, pow(up, gradientPower));
            float towardSun = pow(max(dot(normalize(vec3(dir.x, 0.0, dir.z) + 1e-4), normalize(vec3(L.x, 0.0, L.z) + 1e-4)), 0.0), 2.0);
            vec3 c = dayCol * day + nightColor * (1.0 - day);
            // sunset glow: low in the sky, strongest toward the sun, lasting while the sun is just below the horizon
            c += sunsetColor * sunsetGlow * sunset * (0.25 + 0.75 * towardSun) * pow(1.0 - up, sunsetSpread);
            c *= mix(1.0, groundDarkening, clamp(-dir.y * 4.0, 0.0, 1.0));
            // sun and moon discs
            float sd = dot(dir, L), md = dot(dir, -L);
            c += sunColor * (smoothstep(1.0 - sunSize, 1.0 - sunSize * 0.7, sd) * sunBrightness + pow(max(sd, 0.0), sunGlowSharpness) * sunGlow) * step(0.0, sunUp + 0.05);
            c += moonColor * smoothstep(1.0 - moonSize, 1.0 - moonSize * 0.7, md) * (1.0 - day);
            // stars: a fixed pattern of the sky, at night
            vec3 sp = floor(dir * starScale);
            float star = step(1.0 - starDensity, fract(sin(dot(sp, vec3(12.9898, 78.233, 37.719))) * 43758.5453));
            c += vec3(star) * starBrightness * (1.0 - day) * step(0.0, dir.y);
            // clouds: a noise layer on a plane cloudHeight above the world, drifting with the wind
            if (cloudCoverage > 0.0 && dir.y > 0.01) {
                float camY = mosaicEye().y + frame.cameraPosition.y;
                float tc = (cloudHeight - camY) / dir.y;
                if (tc > 0.0) {
                    vec2 wp = (frame.cameraPosition.xz + mosaicEye().xz + dir.xz * tc) * cloudScale + windDirection * frame.timing.x * windSpeed;
                    float n = mosaicNoise(vec3(wp, frame.timing.x * cloudChange), cloudDetail, 0.55, 0.0);
                    float dens = smoothstep(1.0 - cloudCoverage, 1.0 - cloudCoverage + cloudSoftness, n);
                    dens *= clamp(1.0 - tc / cloudFadeDistance, 0.0, 1.0);
                    vec3 sunTint = mix(sunsetColor, vec3(1.0), smoothstep(0.0, sunsetHeight, sunUp));
                    vec3 cloud = mix(cloudShadowColor, cloudColor * sunTint, clamp(day + 0.1, 0.0, 1.0) * (1.0 - n * cloudSelfShadow))
                        * max(day, nightCloudLevel);
                    c = mix(c, cloud, dens * cloudOpacity);
                }
            }
            return vec4(c, color.a);""");
        add("Render pipeline", "vec3 torchLightShadowed(vec3 position, vec3 normal, float blockLight, vec3 tint = vec3(1.0, 0.6, 0.3), float intensity = 2, float falloff = 2.5, float shadowStrength = 0.85, float wrap = 0.35, float probe = 0.6, float bias = 0.05, float blocksPerLevel = 15)",
            "Block light (directional, shadowed)", "Torch, lamp and lava light with a direction and shadows: the light comes from where the"
                + " block light level rises (its gradient in the world volume), is shaded by the angle to the surface (wrap softens it)"
                + " and a ray toward the source darkens it by shadowStrength when a block is in between.", """
            float b = clamp(blockLight, 0.0, 1.0);
            if (b <= 0.0) return vec3(0.0);
            vec3 p = position + normal * probe;
            vec3 g = vec3(mosaicBlockLightAt(p + vec3(1.0, 0.0, 0.0)) - mosaicBlockLightAt(p - vec3(1.0, 0.0, 0.0)),
                mosaicBlockLightAt(p + vec3(0.0, 1.0, 0.0)) - mosaicBlockLightAt(p - vec3(0.0, 1.0, 0.0)),
                mosaicBlockLightAt(p + vec3(0.0, 0.0, 1.0)) - mosaicBlockLightAt(p - vec3(0.0, 0.0, 1.0)));
            float level = pow(b, falloff) * intensity;
            if (length(g) < 1e-4) return tint * level;
            vec3 ld = normalize(g);
            float dist = max((1.0 - b) * blocksPerLevel, 0.5);
            vec3 n2;
            float m2;
            float h = mosaicVoxelTrace(position + normal * bias, ld, max(dist - 0.5, 0.1), n2, m2);
            float vis = h >= 0.0 ? 1.0 - shadowStrength : 1.0;
            float ndl = max((dot(normal, ld) + wrap) / (1.0 + wrap), 0.0);
            return tint * level * ndl * vis;""");
        add("Render pipeline", "vec4 brightPass(vec4 color, float threshold = 1.2, float knee = 0.6, float clampMax = 64)",
            "Bright pass", "What glows: the part of HDR light above threshold with a soft knee, capped at clampMax (no fireflies);"
                + " feed it into a Buffer at 1/2 for a bloom chain.", """
            float l = max(max(color.r, color.g), color.b);
            float soft = clamp(l - threshold + knee, 0.0, 2.0 * knee);
            soft = soft * soft / (4.0 * knee + 1e-4);
            float w = max(soft, l - threshold) / max(l, 1e-4);
            return vec4(min(color.rgb * w, vec3(clampMax)), 1.0);""");
        add("Render pipeline", "vec4 bloomMix(vec4 base, vec4 a, vec4 b, vec4 c, float strength = 0.06, float weightA = 1, float weightB = 1, float weightC = 1, vec3 tint = vec3(1.0))",
            "Bloom mix", "Adds three bloom levels (1/2, 1/4, 1/8 buffers, blurred) to the HDR image, each with its weight, tinted.", """
            return vec4(base.rgb + (a.rgb * weightA + b.rgb * weightB + c.rgb * weightC) * strength * tint, base.a);""");
        add("Render pipeline", "vec4 logLuminance(vec4 color, float floorLuminance = 0.0001)", "Log luminance",
            "log2 of the brightness of HDR light (in every channel): what Average (buffer) averages for auto exposure.", """
            float l = max(dot(color.rgb, vec3(0.2126, 0.7152, 0.0722)), floorLuminance);
            return vec4(vec3(log2(l)), 1.0);""");
        add("Render pipeline", "vec4 adaptExposure(vec4 average, vec4 previous, float speedUp = 3, float speedDown = 1.2)",
            "Adapt exposure", "Eye adaptation: moves last frame's average log brightness (previous, from Buffer last frame) toward this"
                + " frame's (average) - faster when it gets brighter (speedUp) than darker (speedDown), per second. Store it in a tiny Buffer.", """
            if (previous.a < 0.5) return vec4(average.x, average.x, average.x, 1.0);
            float speed = average.x > previous.x ? speedUp : speedDown;
            float k = 1.0 - exp(-max(frame.timing.y, 0.0) * speed);
            float v = mix(previous.x, average.x, clamp(k, 0.0, 1.0));
            return vec4(v, v, v, 1.0);""");
        add("Render pipeline", "vec4 autoExposure(vec4 color, vec4 adapted, float key = 0.18, float minEV = -4, float maxEV = 6, float compensation = 0)",
            "Auto exposure", "Scales HDR light so the adapted average brightness lands on key (middle grey), within minEV..maxEV stops,"
                + " plus compensation stops. Put it before Tone map.", """
            float ev = clamp(log2(key) - adapted.x, minEV, maxEV) + compensation;
            return vec4(color.rgb * exp2(ev), color.a);""");
        add("Render pipeline", "vec4 volumetricClouds(vec4 color, vec2 uv, float bottom = 170, float top = 240, float coverage = 0.5, float density = 0.035, float scale = 0.006, float detail = 3, float steps = 14, float lightSteps = 3, float absorption = 1.2, vec3 sunColor = vec3(1.0, 0.95, 0.88), float sunStrength = 3, vec3 ambientColor = vec3(0.55, 0.65, 0.8), float ambientStrength = 0.6, float forwardScatter = 0.6, float powder = 0.5, vec2 windDirection = vec2(1.0, 0.3), float windSpeed = 2, float fadeDistance = 9000, float nightLevel = 0.04)",
            "Clouds (volumetric)", "Ray-marched clouds in a slab between bottom and top (blocks above sea level 0): noise density with"
                + " coverage, light marched toward the sun (Beer and powder), forward scattering toward the sun, sky ambient, drifting"
                + " with the wind. Drawn over sky pixels; steps and lightSteps trade quality for speed.", """
            if (mosaicDepth(uv) < 1.0e5) return color;
            vec3 d = mosaicViewDir(uv);
            if (d.y <= 0.01) return color;
            vec3 cam = frame.cameraPosition.xyz + mosaicEye();
            float t0 = (bottom - cam.y) / d.y, t1 = (top - cam.y) / d.y;
            if (t1 <= 0.0) return color;
            t0 = max(t0, 0.0);
            vec3 L = mosaicSunDir();
            float day = clamp(L.y * 4.0 + 0.3, 0.0, 1.0);
            if (L.y < 0.0) L = -L;
            int n = int(clamp(steps, 4.0, 64.0)), nl = int(clamp(lightSteps, 1.0, 8.0));
            float dt = (t1 - t0) / float(n);
            float jitter = fract(52.9829189 * fract(dot(uv * frame.viewSize.xy, vec2(0.06711056, 0.00583715))) + frame.timing.z * 0.618);
            vec3 wind = vec3(windDirection.x, 0.0, windDirection.y) * frame.timing.x * windSpeed;
            float cosT = dot(d, L);
            float phase = mix(1.0, (1.0 - forwardScatter * forwardScatter) / pow(1.0 + forwardScatter * forwardScatter - 2.0 * forwardScatter * cosT, 1.5), 0.5);
            vec3 light = vec3(0.0);
            float trans = 1.0;
            for (int i = 0; i < 64; i++) {
                if (i >= n || trans < 0.02) break;
                vec3 p = cam + d * (t0 + (float(i) + jitter) * dt);
                float h = clamp((p.y - bottom) / max(top - bottom, 1.0), 0.0, 1.0);
                float shape = 4.0 * h * (1.0 - h);
                float nse = mosaicNoise((p + wind) * scale, detail, 0.5, 0.0);
                float dens = max(nse - (1.0 - coverage), 0.0) * shape * density * 10.0;
                if (dens <= 0.0) continue;
                float lightDepth = 0.0;
                for (int j = 0; j < 8; j++) {
                    if (j >= nl) break;
                    vec3 q = p + L * (float(j) + 0.5) * (top - bottom) / float(nl) * 0.5;
                    float hq = clamp((q.y - bottom) / max(top - bottom, 1.0), 0.0, 1.0);
                    lightDepth += max(mosaicNoise((q + wind) * scale, detail, 0.5, 0.0) - (1.0 - coverage), 0.0) * 4.0 * hq * (1.0 - hq);
                }
                float beer = exp(-lightDepth * density * 10.0 * absorption * (top - bottom) / float(nl) * 0.5);
                float pow_ = 1.0 - exp(-dens * dt * 2.0) * powder;
                vec3 lit = sunColor * sunStrength * beer * pow_ * phase * day + ambientColor * ambientStrength * max(day, nightLevel);
                float a = 1.0 - exp(-dens * dt * absorption);
                light += trans * a * lit;
                trans *= 1.0 - a;
            }
            float fade = clamp(1.0 - t0 / fadeDistance, 0.0, 1.0);
            return vec4(mix(color.rgb, color.rgb * trans + light, fade), color.a);""");
        add("Render pipeline", "vec4 waterBody(vec4 color, vec2 uv, vec3 deepColor = vec3(0.01, 0.06, 0.09), vec3 absorption = vec3(0.45, 0.12, 0.08), float refraction = 0.015, float clarity = 1, float gamma = 2.2, float maxDepth = 32)",
            "Water body", "What lies under a water surface, seen through it: the image refracted by the waves (refraction), colours absorbed"
                + " per block of water (absorption per channel, clarity), fading into deepColor. Linear light; put it before reflections.", """
            if (mosaicHitMaterial(uv) != 1.0 || mosaicUnderwater() > 0.5) return color;
            float d = min(mosaicWaterDepth(uv), maxDepth);
            vec3 w = mosaicWorldPos(uv);
            float tm = frame.timing.x;
            vec2 off = vec2(sin(w.x * 1.9 + tm * 1.4) + sin(w.z * 2.7 - tm), cos(w.z * 2.1 + tm * 1.2) + cos(w.x * 3.1 + tm * 0.8)) * refraction;
            vec3 under = pow(max(mosaicScene(clamp(uv + off, vec2(0.0), vec2(1.0))).rgb, vec3(0.0)), vec3(gamma));
            vec3 keep = exp(-absorption * d / max(clarity, 0.01));
            return vec4(mix(deepColor, under, keep) * 0.5 + color.rgb * 0.5 * keep, color.a);""");
        add("Render pipeline", "vec3 skyAmbient(vec3 normal, float skyLight = 1, float intensity = 0.6, float skyCurve = 2, float upBias = 1)", "Sky ambient",
            "Cheap light from the sky dome on a surface (no rays): the sky colour above the normal (upBias tilts it up), fading with"
                + " the sky light level to the power skyCurve (0 in caves). Use it instead of Diffuse GI on slow GPUs.", """
            return mosaicSky(normalize(normal + vec3(0.0, upBias, 0.0))) * pow(clamp(skyLight, 0.0, 1.0), skyCurve) * intensity;""");
        add("Render pipeline", "vec3 torchLight(float blockLight, vec3 tint = vec3(1.0, 0.6, 0.3), float intensity = 2, float falloff = 2.5)", "Block light",
            "Light of torches, lamps and lava from the block light level (0..1, Block light node), in the colour you pick; falloff"
                + " shapes how fast it fades from the source.", """
            return tint * pow(clamp(blockLight, 0.0, 1.0), falloff) * intensity;""");
        add("Render pipeline", "vec4 diffuseGI(vec3 position, vec3 normal, float bounces = 1, float maxDistance = 24, float sunStrength = 2.2, float skyStrength = 1, float shadowDistance = 48, vec3 sunColor = vec3(1.0, 0.95, 0.88), vec3 sunsetColor = vec3(1.0, 0.45, 0.18), vec3 moonColor = vec3(0.32, 0.4, 0.62), float moonLevel = 0.06, float hitSkyAmbient = 0.3, vec3 torchColor = vec3(1.0, 0.6, 0.3), float torchStrength = 1.5, float glowStrength = 2, float bias = 0.03)",
            "Diffuse GI (voxel paths)", "Light arriving at a surface from everywhere, one random path per pixel and frame through the"
                + " world volume (maxDistance, up to 4 bounces): sky through openings (skyStrength), sun bounced off lit blocks (its own"
                + " colours, shadowDistance), torch light and glowing blocks. Noisy by design: Accumulate into a Buffer (1/2 size)"
                + " and Denoise it, then add it to the light. No ray tracing hardware needed.", """
            if (length(position) > 4000.0) return vec4(0.0, 0.0, 0.0, 1.0);
            vec3 L = mosaicSunDir();
            float moon = L.y < 0.0 ? 1.0 : 0.0;
            if (L.y < 0.0) L = -L;
            vec3 sunCol = mix(sunsetColor, sunColor, smoothstep(0.0, 0.35, L.y));
            sunCol = mix(sunCol, moonColor * moonLevel, moon) * smoothstep(0.0, 0.08, L.y) * sunStrength;
            vec3 pos = position + normal * bias, nrm = normal, through = vec3(1.0), sum = vec3(0.0);
            float f = frame.timing.z;
            vec3 h = mosaicHash33(position * 3.17 + vec3(f * 0.6180339, f * 0.4142135, f * 0.7320508));
            int nb = int(clamp(bounces, 1.0, 4.0));
            for (int b = 0; b < 4; b++) {
                if (b >= nb) break;
                if (b > 0) h = mosaicHash33(h * 17.31 + vec3(float(b)));
                vec3 tg = normalize(abs(nrm.y) < 0.99 ? cross(nrm, vec3(0.0, 1.0, 0.0)) : cross(nrm, vec3(1.0, 0.0, 0.0)));
                vec3 bt = cross(nrm, tg);
                float phi = 6.2831853 * h.x, cr = sqrt(h.y);
                vec3 dir = normalize(tg * cos(phi) * cr + bt * sin(phi) * cr + nrm * sqrt(max(1.0 - h.y, 0.0)));
                vec3 hn, sn;
                float hm, sm;
                float tt = mosaicVoxelTrace(pos, dir, maxDistance, hn, hm);
                if (tt < 0.0) {
                    sum += through * mosaicSky(dir) * skyStrength;
                    break;
                }
                vec3 hp = pos + dir * tt;
                vec3 alb = mosaicAlbedo(hm);
                float lamp = mosaicBlockLightAt(hp + hn * 0.5), sky = mosaicSkyLightAt(hp + hn * 0.5);
                float ndl = max(dot(hn, L), 0.0);
                float vis = ndl > 0.0 && mosaicVoxelTrace(hp + hn * 0.01, L, shadowDistance, sn, sm) < 0.0 ? 1.0 : 0.0;
                vec3 glow = (hm == 2.0 || hm == 13.0) ? alb * glowStrength : vec3(0.0);
                sum += through * (alb * (sunCol * ndl * vis * sky + mosaicSky(hn) * hitSkyAmbient * sky * skyStrength
                    + torchColor * lamp * lamp * torchStrength) + glow);
                through *= alb;
                pos = hp + hn * 0.01;
                nrm = hn;
            }
            return vec4(sum, 1.0);""");
        add("Render pipeline", "vec3 voxelReflection(vec3 position, vec3 normal, float maxDistance = 64, float sunStrength = 3, vec3 sunColor = vec3(1.0, 0.95, 0.88), float shadowDistance = 48, float skyAmbient = 0.4, vec3 torchColor = vec3(1.0, 0.6, 0.3), float torchStrength = 1.5, float bias = 0.03)",
            "Reflection (voxels)", "What a mirror at the surface would show, traced through the world volume: lit block colours, the sky"
                + " where the ray escapes. Mix it in by Fresnel for water, glass and metal.", """
            vec3 v = normalize(position - mosaicEye());
            vec3 r = reflect(v, normal), hn, sn;
            float hm, sm;
            float t = mosaicVoxelTrace(position + normal * bias, r, maxDistance, hn, hm);
            if (t < 0.0) return mosaicSky(r);
            vec3 hp = position + normal * bias + r * t;
            vec3 L = mosaicSunDir();
            if (L.y < 0.0) L = -L;
            float ndl = max(dot(hn, L), 0.0);
            float vis = ndl > 0.0 && mosaicVoxelTrace(hp + hn * 0.01, L, shadowDistance, sn, sm) < 0.0 ? 1.0 : 0.0;
            float sky = mosaicSkyLightAt(hp + hn * 0.5), lamp = mosaicBlockLightAt(hp + hn * 0.5);
            return mosaicAlbedo(hm) * (sunColor * sunStrength * ndl * vis * sky * smoothstep(0.0, 0.08, L.y)
                + mosaicSky(hn) * skyAmbient * sky + torchColor * lamp * lamp * torchStrength);""");
        add("Render pipeline", "vec3 selfGlow(vec2 uv, vec3 albedo, float strength = 2, float lavaStrength = 2)", "Emission",
            "Light a surface gives off itself: light-source blocks (strength) and lava (lavaStrength) seen at this pixel glow in their own colour.", """
            float m = mosaicHitMaterial(uv);
            return m == 13.0 ? albedo * strength : (m == 2.0 ? albedo * lavaStrength : vec3(0.0));""");
        add("Render pipeline", "vec4 litSurface(vec2 uv, vec3 albedo, vec3 light, vec3 glow = vec3(0.0), float skyGamma = 2.2, float skyBrightness = 1)", "Combine (shade)",
            "The lit image in linear HDR: albedo times the sum of the lights, plus emission; the sky keeps the game's own sky (made"
                + " linear with skyGamma, scaled by skyBrightness).", """
            if (mosaicDepth(uv) >= 1.0e5) return vec4(pow(max(mosaicScene(uv).rgb, vec3(0.0)), vec3(skyGamma)) * skyBrightness, 1.0);
            return vec4(albedo * light + glow, 1.0);""");
        add("Render pipeline", "vec4 reflectiveSurface(vec4 color, vec2 uv, float material = 1, float waves = 1, float strength = 1, float waveScale = 1, float waveSpeed = 1, float waveHeight = 0.08, float maxDistance = 64, float f0 = 0.02, vec3 sunColor = vec3(1.6, 1.5, 1.4), float skyAmbient = 0.4, float minLight = 0.05, float shadowDistance = 48)",
            "Reflective surface (water)", "Mirror reflections traced through the world volume on one material (1 water, 10 ice, 11 glass,"
                + " 12 metal), mixed in by Fresnel (f0); on water the normal moves with waves (scale, speed, height). Works on linear"
                + " (before Tone map) or display colour.", """
            float m = mosaicHitMaterial(uv);
            if (mosaicDepth(uv) >= 1.0e5 || m != floor(material + 0.5)) return color;
            vec3 p = mosaicHitPos(uv), n = mosaicNormal(uv);
            if (waves > 0.0 && n.y > 0.5) {
                vec3 w = mosaicWorldPos(uv) * waveScale;
                float t = frame.timing.x * waveSpeed;
                float hx = sin(w.x * 1.7 + t * 1.3) * 0.5 + sin(w.z * 2.3 - t * 1.1 + w.x * 0.7) * 0.35 + sin((w.x + w.z) * 4.1 + t * 2.1) * 0.15;
                float hz = cos(w.z * 1.9 + t * 1.2) * 0.5 + cos(w.x * 2.1 + t * 0.9 - w.z * 0.6) * 0.35 + cos((w.x - w.z) * 3.7 - t * 1.7) * 0.15;
                n = normalize(vec3(-hx * waveHeight * waves, 1.0, -hz * waveHeight * waves));
            }
            vec3 v = normalize(p - mosaicEye());
            vec3 r = reflect(v, n), hn, sn;
            if (r.y < 0.02 && n.y > 0.5) r.y = 0.02;
            float hm, sm;
            vec3 o = p + n * 0.05;
            float th = mosaicVoxelTrace(o, normalize(r), maxDistance, hn, hm);
            vec3 refl = mosaicSky(normalize(r));
            if (th >= 0.0) {
                vec3 hp = o + normalize(r) * th;
                vec3 L = mosaicSunDir();
                if (L.y < 0.0) L = -L;
                float ndl = max(dot(hn, L), 0.0);
                float vis = ndl > 0.0 && mosaicVoxelTrace(hp + hn * 0.01, L, shadowDistance, sn, sm) < 0.0 ? 1.0 : 0.0;
                float sky = mosaicSkyLightAt(hp + hn * 0.5);
                refl = mosaicAlbedo(hm) * (sunColor * ndl * vis * sky + mosaicSky(hn) * skyAmbient * sky + minLight);
            }
            float fres = f0 + (1.0 - f0) * pow(1.0 - max(dot(-v, n), 0.0), 5.0);
            return vec4(mix(color.rgb, refl, clamp(fres * strength, 0.0, 1.0)), color.a);""");
        add("Render pipeline", "vec3 sunLightRT(vec3 position, vec3 normal, float intensity = 2.2, vec3 sunColor = vec3(1.0, 0.95, 0.88), vec3 sunsetColor = vec3(1.0, 0.45, 0.18), float sunsetHeight = 0.35, vec3 moonColor = vec3(0.32, 0.4, 0.62), float moonLevel = 0.06, float horizonFade = 0.08, float bias = 0.02, float biasPerBlock = 0.001)",
            "Sun & moon light (RT)", "Sun & moon light with an exact shadow ray through the real scene geometry (hardware ray tracing:"
                + " an RTX-class GPU); the same colours and fades as Sun & moon light.", """
            vec3 L = mosaicSunDir();
            float moon = L.y < 0.0 ? 1.0 : 0.0;
            if (L.y < 0.0) L = -L;
            float ndl = max(dot(normal, L), 0.0);
            if (ndl <= 0.0 || length(position) > 4000.0) return vec3(0.0);
            float vis = mosaicShadow(position + normal * (bias + length(position) * biasPerBlock), L);
            vec3 col = mix(sunsetColor, sunColor, smoothstep(0.0, sunsetHeight, L.y));
            col = mix(col, moonColor * moonLevel, moon);
            return col * ndl * vis * intensity * smoothstep(0.0, horizonFade, L.y);""");
        add("Render pipeline", "vec4 diffuseGIRT(vec3 position, vec3 normal, float maxDistance = 64, float sunStrength = 2.2, float skyStrength = 1, vec3 sunColor = vec3(1.0, 0.95, 0.88), vec3 sunsetColor = vec3(1.0, 0.45, 0.18), vec3 moonColor = vec3(0.32, 0.4, 0.62), float moonLevel = 0.06, float hitSunShare = 0.6, float hitSkyAmbient = 0.3, vec3 unknownAlbedo = vec3(0.4), vec3 torchColor = vec3(1.0, 0.6, 0.3), float torchStrength = 1.5, float glowStrength = 2, float bias = 0.03)",
            "Diffuse GI (RT)", "One-bounce light from a hardware ray per pixel and frame through the real scene geometry; the hit is shaded"
                + " with the world volume's block colour (unknownAlbedo outside it), a shadow ray toward the sun and its sky light."
                + " Accumulate and Denoise it like Diffuse GI. Needs hardware ray tracing.", """
            if (length(position) > 4000.0) return vec4(0.0, 0.0, 0.0, 1.0);
            vec3 L = mosaicSunDir();
            float moon = L.y < 0.0 ? 1.0 : 0.0;
            if (L.y < 0.0) L = -L;
            vec3 sunCol = mix(sunsetColor, sunColor, smoothstep(0.0, 0.35, L.y));
            sunCol = mix(sunCol, moonColor * moonLevel, moon) * smoothstep(0.0, 0.08, L.y) * sunStrength;
            float f = frame.timing.z;
            vec3 h = mosaicHash33(position * 3.17 + vec3(f * 0.6180339, f * 0.4142135, f * 0.7320508));
            vec3 tg = normalize(abs(normal.y) < 0.99 ? cross(normal, vec3(0.0, 1.0, 0.0)) : cross(normal, vec3(1.0, 0.0, 0.0)));
            vec3 bt = cross(normal, tg);
            float phi = 6.2831853 * h.x, cr = sqrt(h.y);
            vec3 dir = normalize(tg * cos(phi) * cr + bt * sin(phi) * cr + normal * sqrt(max(1.0 - h.y, 0.0)));
            vec3 o = position + normal * bias;
            float t = mosaicTrace(o, dir, maxDistance);
            if (t < 0.0) return vec4(mosaicSky(dir) * skyStrength, 1.0);
            vec3 hp = o + dir * t;
            float m = mosaicMaterialAt(hp + dir * 0.05);
            vec3 alb = m >= 2.0 ? mosaicAlbedo(m) : unknownAlbedo;
            float sky = mosaicSkyLightAt(hp - dir * 0.3), lamp = mosaicBlockLightAt(hp - dir * 0.3);
            float vis = mosaicShadow(hp - dir * 0.02, L);
            vec3 glow = (m == 2.0 || m == 13.0) ? alb * glowStrength : vec3(0.0);
            return vec4(alb * (sunCol * hitSunShare * vis * sky + mosaicSky(-dir) * hitSkyAmbient * sky * skyStrength
                + torchColor * lamp * lamp * torchStrength) + glow, 1.0);""");
        add("Render pipeline", "vec4 tonemap(vec4 color, float exposure = 0, float curve = 0, float gamma = 2.2, float whitePoint = 11.2, float saturation = 1)", "Tone map",
            "Linear HDR light to the screen: exposure in stops, then a curve (0 ACES, 1 Reinhard, 2 Hable filmic with whitePoint,"
                + " 3 exponential, 4 clip), saturation and the display gamma. The last step of a renderer of your own.", """
            vec3 c = max(color.rgb, vec3(0.0)) * exp2(exposure);
            int k = int(curve + 0.5);
            if (k == 0) c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
            else if (k == 1) c = c / (1.0 + dot(c, vec3(0.2126, 0.7152, 0.0722)));
            else if (k == 2) {
                vec3 x = c * 2.0;
                float wp = whitePoint;
                float white = (wp * (0.15 * wp + 0.05) + 0.004) / (wp * (0.15 * wp + 0.5) + 0.06) - 0.0666667;
                c = ((x * (0.15 * x + 0.05) + 0.004) / (x * (0.15 * x + 0.5) + 0.06) - 0.0666667) / white;
            }
            else if (k == 3) c = vec3(1.0) - exp(-c);
            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(vec3(l), c, saturation);
            return vec4(pow(clamp(c, vec3(0.0), vec3(1.0)), vec3(1.0 / max(gamma, 0.1))), color.a);""");
    }

    /** Every built-in effect, in palette order. */
    public static List<MosaicFunction> all() { return new ArrayList<>(ALL.values()); }

    public static MosaicFunction get(String name) { return ALL.get(name); }

    /** True when a function is a built-in effect with its original code (the preview has a CPU twin for it). */
    public static boolean isStock(MosaicFunction f) {
        MosaicFunction s = ALL.get(f.name());
        return s != null && s.glsl().equals(f.glsl()) && s.signature().equals(f.signature());
    }
}
