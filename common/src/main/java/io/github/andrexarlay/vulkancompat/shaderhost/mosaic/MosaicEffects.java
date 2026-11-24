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

    private static void add(String signature, String label, String help, String body) {
        add("Effects", signature, label, help, body);
    }

    private static void add(String category, String signature, String label, String help, String body) {
        MosaicFunction f = MosaicFunction.parseSignature(signature, body);
        ALL.put(f.name(), new MosaicFunction(f.name(), label, category, help, f.params(), f.output(), body, null));
    }

    static {
        add("vec4 vignette(vec4 color, vec2 uv, float strength = 0.6, float radius = 0.75)", "Vignette",
            "Darkens the corners of the screen.", """
            vec2 d = uv - 0.5;
            float v = smoothstep(radius, radius - 0.45, length(d) * 1.4142);
            return vec4(color.rgb * mix(1.0 - strength, 1.0, v), color.a);""");
        add("vec4 colorGrade(vec4 color, float exposure = 0, float contrast = 1, float saturation = 1, vec3 tint = 1)",
            "Color grade", "Exposure (stops), contrast, saturation and a color tint.", """
            vec3 c = color.rgb * exp2(exposure) * tint;
            c = (c - 0.18) * contrast + 0.18;
            float l = dot(c, vec3(0.2126, 0.7152, 0.0722));
            c = mix(vec3(l), c, saturation);
            return vec4(max(c, 0.0), color.a);""");
        add("vec4 filmGrain(vec4 color, vec2 uv, float amount = 0.05)", "Film grain",
            "Animated noise like film grain.", """
            vec2 p = uv * frame.viewSize.xy + frame.timing.x * 61.0;
            float n = fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            return vec4(color.rgb + (n - 0.5) * amount, color.a);""");
        add("vec4 chromaticAberration(vec2 uv, float amount = 0.004)", "Chromatic aberration",
            "Splits red and blue toward the screen edges, like a cheap lens.", """
            vec2 d = (uv - 0.5) * amount * 2.0;
            float r = mosaicScene(uv + d).r;
            float g = mosaicScene(uv).g;
            float b = mosaicScene(uv - d).b;
            return vec4(r, g, b, 1.0);""");
        add("vec4 sharpen(vec2 uv, float amount = 0.5)", "Sharpen",
            "Boosts edges with a 5-tap unsharp mask of the world image.", """
            vec2 px = frame.viewSize.zw;
            vec4 c = mosaicScene(uv);
            vec4 n = mosaicScene(uv + vec2(px.x, 0.0)) + mosaicScene(uv - vec2(px.x, 0.0))
                   + mosaicScene(uv + vec2(0.0, px.y)) + mosaicScene(uv - vec2(0.0, px.y));
            return vec4(max(c.rgb + (c.rgb * 4.0 - n.rgb) * amount, 0.0), c.a);""");
        add("vec4 depthFog(vec4 color, vec2 uv, vec3 fogColor = vec3(0.62, 0.72, 0.85), float start = 16, float end = 160)",
            "Distance fog", "Fades distant blocks into a fog color; the sky stays as it is.", """
            float d = mosaicDepth(uv);
            float f = d >= 1.0e5 ? 0.0 : smoothstep(start, end, d);
            return vec4(mix(color.rgb, fogColor, f), color.a);""");
        add("vec4 posterize(vec4 color, float steps = 6)", "Posterize",
            "Reduces colors to a few bands (comic / toon look).", """
            return vec4(floor(color.rgb * steps + 0.5) / steps, color.a);""");
        add("vec4 scanlines(vec4 color, vec2 uv, float strength = 0.25, float density = 1)", "Scanlines",
            "Horizontal CRT lines.", """
            float s = 0.5 + 0.5 * sin(uv.y * frame.viewSize.y * 3.14159 * density);
            return vec4(color.rgb * (1.0 - strength * s), color.a);""");
        add("vec4 depthOutline(vec4 color, vec2 uv, vec3 lineColor = vec3(0.0), float threshold = 0.08)", "Outline",
            "Draws lines where the distance jumps (block silhouettes).", """
            vec2 px = frame.viewSize.zw;
            float d = min(mosaicDepth(uv), 1000.0);
            float dx = abs(min(mosaicDepth(uv + vec2(px.x, 0.0)), 1000.0) - d) + abs(min(mosaicDepth(uv - vec2(px.x, 0.0)), 1000.0) - d);
            float dy = abs(min(mosaicDepth(uv + vec2(0.0, px.y)), 1000.0) - d) + abs(min(mosaicDepth(uv - vec2(0.0, px.y)), 1000.0) - d);
            float edge = step(threshold * max(d, 1.0), dx + dy);
            return vec4(mix(color.rgb, lineColor, edge), color.a);""");
        add("vec4 glow(vec4 color, vec2 uv, float threshold = 1, float intensity = 0.4, float radius = 6)", "Glow",
            "Cheap bloom: bright areas bleed light around them (8 taps).", """
            vec2 px = frame.viewSize.zw * radius;
            vec3 acc = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398;
                vec3 s = mosaicScene(uv + vec2(cos(a), sin(a)) * px).rgb;
                acc += max(s - threshold, 0.0);
            }
            return vec4(color.rgb + acc / 8.0 * intensity, color.a);""");
        add("vec4 heatHaze(vec2 uv, float amount = 0.002, float speed = 2)", "Heat haze",
            "Wobbles the image like hot air.", """
            float t = frame.timing.x * speed;
            vec2 o = vec2(sin(uv.y * 40.0 + t), cos(uv.x * 35.0 + t * 1.3)) * amount;
            return mosaicScene(uv + o);""");
        add("vec4 skyShadow(vec4 color, vec2 uv, float darkness = 0.45)", "Sky shadow (ray traced)",
            "Traces one ray per pixel toward the sky and darkens what is blocked.", """
            float t = mosaicDepth(uv);
            if (t >= 1.0e5) return color;
            vec3 p = mosaicEye() + mosaicViewDir(uv) * (t - 0.01);
            float lit = mosaicShadow(p, normalize(vec3(0.32, 0.90, 0.28)));
            return vec4(color.rgb * mix(1.0 - darkness, 1.0, lit), color.a);""");
    }

    static {
        add("vec4 waterColor(vec4 color, vec2 uv, vec3 deepColor = vec3(0.02, 0.16, 0.22), float clarity = 3, float tint = 0.6)",
            "Water color", "Absorbs light by how much water the view crosses: shallow water stays clear, deep water"
                + " turns to the deep color.", """
            float d = mosaicWaterDepth(uv);
            if (d <= 0.0) return color;
            float keep = exp(-d / max(clarity, 0.01));
            vec3 absorbed = color.rgb * mix(vec3(1.0), vec3(0.55, 0.85, 0.95), tint) * keep;
            return vec4(absorbed + deepColor * (1.0 - keep), color.a);""");
        add("vec4 underwater(vec4 color, vec2 uv, vec3 fogColor = vec3(0.04, 0.22, 0.32), float density = 0.09, float wobble = 0.003)",
            "Underwater", "While the camera is in water: wobble, blue tint and fog by distance.", """
            if (mosaicUnderwater() < 0.5) return color;
            float t = frame.timing.x;
            vec2 w = uv + vec2(sin(uv.y * 30.0 + t * 2.0), cos(uv.x * 25.0 + t * 1.7)) * wobble;
            vec4 c = mosaicScene(w);
            float f = 1.0 - exp(-min(mosaicDepth(w), 200.0) * density);
            return vec4(mix(c.rgb * vec3(0.6, 0.9, 1.0), fogColor, f), c.a);""");
        add("vec4 caustics(vec4 color, vec2 uv, float strength = 0.4, float scale = 1)", "Water caustics",
            "Animated light ripples on blocks that lie under water.", """
            float t = mosaicDepth(uv);
            if (t >= 1.0e5) return color;
            vec3 rel = mosaicEye() + mosaicViewDir(uv) * t;
            if (mosaicMaterialAt(rel + vec3(0.0, 0.6, 0.0)) != 1.0) return color;
            vec2 p = (frame.cameraPosition.xz + rel.xz) * 1.7 * scale;
            float tm = frame.timing.x * 0.8, c = 0.0;
            for (int i = 0; i < 3; i++) {
                p = p * 1.31 + vec2(sin(p.y + tm), cos(p.x - tm));
                c += abs(sin(p.x) * cos(p.y));
            }
            c = pow(clamp(1.0 - c / 3.0, 0.0, 1.0), 4.0);
            return vec4(color.rgb * (1.0 + c * strength * 4.0), color.a);""");
        add("vec4 waterSparkle(vec4 color, vec2 uv, float strength = 0.6)", "Water sparkle",
            "Twinkling sun glints on the water surface.", """
            if (mosaicWaterDepth(uv) <= 0.0 || mosaicUnderwater() > 0.5) return color;
            vec3 w = mosaicWorldPos(uv);
            float n = fract(sin(dot(floor(w.xz * 6.0) + floor(frame.timing.x * 4.0), vec2(12.9898, 78.233))) * 43758.5453);
            return vec4(color.rgb + step(0.985, n) * strength, color.a);""");
    }

    static {
        add("vec4 caveDarkness(vec4 color, vec2 uv, float strength = 0.6)", "Cave darkness",
            "Darkens surfaces the sky cannot reach unless a torch or lamp lights them.", """
            float sky = mosaicHitSkyLight(uv), lamp = mosaicHitBlockLight(uv);
            float dark = (1.0 - sky) * (1.0 - lamp);
            return vec4(color.rgb * (1.0 - strength * dark), color.a);""");
        add("vec4 torchWarmth(vec4 color, vec2 uv, vec3 warmColor = vec3(1.0, 0.62, 0.3), float strength = 0.5)",
            "Torch warmth", "Warms surfaces lit by torches, lamps and lava.", """
            float lamp = mosaicHitBlockLight(uv);
            return vec4(color.rgb * mix(vec3(1.0), warmColor * 1.4, lamp * lamp * strength), color.a);""");
        add("vec4 leafGlow(vec4 color, vec2 uv, vec3 glowColor = vec3(0.55, 0.85, 0.2), float strength = 0.35)",
            "Leaf glow", "Leaves let sunlight through when you look toward the sun.", """
            if (mosaicHitMaterial(uv) != 5.0) return color;
            float toward = max(dot(mosaicViewDir(uv), normalize(vec3(0.32, 0.90, 0.28))), 0.0);
            return vec4(color.rgb + glowColor * pow(toward, 3.0) * strength * mosaicHitSkyLight(uv), color.a);""");
        add("vec4 lavaHeat(vec2 uv, float amount = 0.004, float reach = 6)", "Lava heat",
            "Hot air wobble above and around lava.", """
            float lava = mosaicLavaDepth(uv) > 0.0 || mosaicHitMaterial(uv) == 2.0 ? 1.0 : mosaicHitBlockLight(uv);
            float t = frame.timing.x * 3.0;
            vec2 o = vec2(sin(uv.y * 60.0 + t), cos(uv.x * 50.0 + t * 1.2)) * amount * lava * smoothstep(reach, 0.0, min(mosaicDepth(uv), reach));
            return mosaicScene(uv + o);""");
        add("vec4 snowSparkle(vec4 color, vec2 uv, float strength = 0.5)", "Snow sparkle",
            "Tiny glints on snow and ice.", """
            float m = mosaicHitMaterial(uv);
            if (m != 9.0 && m != 10.0) return color;
            vec3 w = mosaicWorldPos(uv);
            float n = fract(sin(dot(floor(w.xz * 16.0 + w.y * 7.0), vec2(12.9898, 78.233)) + floor(frame.timing.x * 2.0)) * 43758.5453);
            return vec4(color.rgb + step(0.992, n) * strength, color.a);""");
        add("vec4 materialTint(vec4 color, vec2 uv, float material = 5, vec3 tint = vec3(1.15, 1.0, 0.85), float amount = 1)",
            "Material tint", "Recolors one material class everywhere: 3 stone, 4 dirt, 5 leaves, 6 plants, 7 wood, 8 sand,"
                + " 9 snow, 10 ice, 11 glass, 12 metal, 13 lights.", """
            if (mosaicHitMaterial(uv) != floor(material + 0.5)) return color;
            return vec4(mix(color.rgb, color.rgb * tint, amount), color.a);""");
    }

    // ---------------------------------------------------------------- rendering: voxels, rays, paths, shading blocks
    static {
        add("Rendering", "vec4 voxelRender(vec2 uv, float shadows = 1, float maxDistance = 96)", "Voxel render",
            "Renders the world volume by itself: block colors, sun with voxel shadows, sky and torch light."
                + " Works without hardware ray tracing.", """
            vec3 o = mosaicEye(), d = mosaicViewDir(uv), n, n2;
            float m, m2;
            float t = mosaicVoxelTrace(o, d, maxDistance, n, m);
            if (t < 0.0) return vec4(mosaicSky(d), 1.0);
            vec3 p = o + d * t;
            vec3 albedo = mosaicAlbedo(m);
            float sky = mosaicSkyLightAt(p + n * 0.5), lamp = mosaicBlockLightAt(p + n * 0.5);
            vec3 L = mosaicSunDir();
            float ndl = max(dot(n, L), 0.0);
            float vis = (shadows > 0.5 && ndl > 0.0) ? (mosaicVoxelTrace(p + n * 0.01, L, 64.0, n2, m2) < 0.0 ? 1.0 : 0.0) : 1.0;
            vec3 light = vec3(1.0, 0.95, 0.85) * ndl * vis * sky + mosaicSky(n) * 0.35 * sky + vec3(1.0, 0.7, 0.4) * lamp * lamp * 1.2;
            vec3 c = albedo * light;
            if (m == 2.0 || m == 13.0) c += albedo * 1.5;
            return vec4(c, 1.0);""");
        add("Rendering", "vec4 pathTraceGI(vec4 color, vec2 uv, float samples = 2, float bounces = 2, float strength = 1, float maxDistance = 32)",
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
                    if (tt < 0.0) { irradiance += throughput * mosaicSky(dir); break; }
                    vec3 hp = pos + dir * tt;
                    vec3 alb = mosaicAlbedo(hm);
                    float lamp = mosaicBlockLightAt(hp + hn * 0.5), sky = mosaicSkyLightAt(hp + hn * 0.5);
                    float ndl = max(dot(hn, L), 0.0);
                    float vis = ndl > 0.0 && mosaicVoxelTrace(hp + hn * 0.01, L, 48.0, sn, sm) < 0.0 ? 1.0 : 0.0;
                    vec3 emit = (hm == 2.0 || hm == 13.0) ? alb * 2.0 : vec3(0.0);
                    irradiance += throughput * alb * (vec3(1.0, 0.95, 0.85) * ndl * vis * sky + vec3(1.0, 0.7, 0.4) * lamp * lamp) + throughput * emit;
                    throughput *= alb;
                    pos = hp + hn * 0.01;
                    nrm = hn;
                }
            }
            irradiance /= float(ns);
            return vec4(color.rgb + color.rgb * irradiance * strength, color.a);""");
        add("Rendering", "vec4 rtAmbientOcclusion(vec4 color, vec2 uv, float radius = 1.5, float samples = 4, float strength = 0.8)",
            "RT ambient occlusion", "Hardware rays around each surface darken corners and crevices.", """
            if (mosaicDepth(uv) >= 1.0e5) return color;
            vec3 n = mosaicNormal(uv), p = mosaicHitPos(uv) + n * 0.02;
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
        add("Rendering", "vec4 rtReflections(vec4 color, vec2 uv, float amount = 0.35, float material = 1)", "RT reflections",
            "Hardware-traced mirror reflections on one material class (1 water by default; -1 = every surface).", """
            float m = mosaicHitMaterial(uv);
            if (mosaicDepth(uv) >= 1.0e5 || (material >= 0.0 && m != floor(material + 0.5))) return color;
            vec3 n = mosaicNormal(uv), v = mosaicViewDir(uv);
            vec3 r = reflect(v, n), p = mosaicHitPos(uv) + n * 0.02;
            float t = mosaicTrace(p, r, 128.0);
            vec3 refl = mosaicSky(r);
            if (t >= 0.0) {
                vec4 clip = frame.projection * (frame.modelView * vec4(p + r * t, 1.0));
                vec2 suv = clip.xy / clip.w * 0.5 + 0.5;
                refl = (clip.w > 0.0 && all(greaterThanEqual(suv, vec2(0.0))) && all(lessThanEqual(suv, vec2(1.0))))
                    ? mosaicScene(suv).rgb : color.rgb * 0.5;
            }
            float fres = 0.02 + 0.98 * pow(1.0 - max(dot(-v, n), 0.0), 5.0);
            return vec4(mix(color.rgb, refl, clamp(amount + fres, 0.0, 1.0) * amount * 2.0), color.a);""");
        // shading building blocks
        add("Shading", "float fresnelSchlick(float cosTheta, float f0 = 0.04)", "Fresnel (Schlick)",
            "How reflective a surface is at a view angle (cosTheta = dot(normal, toViewer)).", """
            return f0 + (1.0 - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);""");
        add("Shading", "float ggxSpecular(vec3 n, vec3 v, vec3 l, float roughness = 0.5)", "GGX specular",
            "Microfacet highlight strength (n normal, v to viewer, l to light).", """
            vec3 h = normalize(v + l);
            float a = max(roughness * roughness, 1e-3), a2 = a * a;
            float nh = max(dot(n, h), 0.0), nv = max(dot(n, v), 1e-4), nl = max(dot(n, l), 0.0);
            float d = a2 / (3.14159265 * pow(nh * nh * (a2 - 1.0) + 1.0, 2.0));
            float k = a * 0.5;
            float g = nv / (nv * (1.0 - k) + k) * nl / (nl * (1.0 - k) + k);
            return d * g / (4.0 * nv + 1e-4);""");
        add("Shading", "float lambert(vec3 n, vec3 l)", "Lambert diffuse", "Diffuse light: dot(normal, toLight), never negative.", """
            return max(dot(normalize(n), normalize(l)), 0.0);""");
        add("Shading", "vec3 acesTonemap(vec3 c)", "ACES tonemap", "Filmic curve from HDR to display range.", """
            return clamp((c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14), 0.0, 1.0);""");
        add("Shading", "vec3 skyColor(vec3 dir)", "Sky color", "The analytic sky in a direction (follows the time of day).", """
            return mosaicSky(normalize(dir));""");
        add("Shading", "vec3 materialColor(float material)", "Material color", "Typical color of a material class.", """
            return mosaicAlbedo(material);""");
        add("Shading", "float voxelDistance(vec3 origin, vec3 dir, float maxDistance = 96)", "Voxel distance",
            "Distance along a ray to the first solid block of the world volume (-1 = none).", """
            vec3 n;
            float m;
            return mosaicVoxelTrace(origin, normalize(dir), maxDistance, n, m);""");
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
