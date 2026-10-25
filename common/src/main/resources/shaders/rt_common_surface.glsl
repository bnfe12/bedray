// Schlick Fresnel
vec3 fresnelSchlick(vec3 f0, float cosTheta) {
    return f0 + (1.0 - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
}

// Roughness above which no specular lobe is sampled (see ROUGH_SPLIT users): such a surface must not be attenuated by the
// grazing Schlick term, because the energy it removes is never traced back as a reflection. With the full curve the
// reflectance at 84 degrees is ~0.6 for a stone-like f0 of 0.04, so the far end of a wall next to the player and the floor
// away from the crosshair darkened by up to 60% - a radial vignette that has nothing to do with the scene lighting.
const float ROUGH_SPLIT = 0.6;

// Reflectance share of the diffuse lobe. Surfaces whose specular lobe is traced (metals, dielectrics below ROUGH_SPLIT) keep
// the angle-dependent Fresnel; a rough dielectric, whose broad specular lobe is not traced, keeps the constant F0 instead.
vec3 fresnelEnergySplit(vec3 f0, float cosTheta, float roughness, bool metal) {
    return (metal || roughness < ROUGH_SPLIT) ? fresnelSchlick(f0, cosTheta) : f0;
}

// Dielectric F0 is the material value (AutoPbr: water/ice 0.02, glass and default 0.04; LabPBR _s green). The earlier
// dielectricF0() boost to ~0.22 for smooth surfaces is gone: it was 5-10x the physical reflectance and compensated a
// double Fresnel weighting of glass reflections in the stochastic transmission code (see interface* below).

// PBR Horizon Bending: smoothly bends a reflection vector above the geometric horizon ng
// to prevent black termination or reflection flipping on normal-mapped surfaces (water ripples, rough metals).
vec3 bendHorizon(vec3 r, vec3 ng) {
    float ndotr = dot(r, ng);
    if (ndotr < 0.015) {
        return normalize(r + ng * (0.015 - ndotr));
    }
    return r;
}

#include "rt_caustics.glsl"

// Water surface normal facing the ray (side of ng) from the analytic wave field (rt_caustics.glsl). footprint = blocks per pixel
// on the surface; lostVar the slope variance of the faded-out waves (roughness of the far sheen). Only horizontal faces get waves:
// the vertical sides of flowing water stay flat.
vec3 waterWaveNormal(vec3 pos, vec3 ng, float time, float footprint, out float lostVar) {
    lostVar = 0.0;
    if (abs(ng.y) < 0.7) {
        return ng;
    }
    // Flowing water (sloped top quads of Minecraft fluids): the wave pattern is carried downhill, the face tilt stays in the normal.
    vec2 xz = pos.xz;
    vec2 downhill = ng.xz * sign(ng.y);
    float flow = length(downhill);
    if (flow > 0.02) {
        xz -= downhill / flow * (time * min(flow * 10.0, 1.6));
    }
    vec2 slope = waterSlope(xz, time, footprint, lostVar);
    // perturb the face normal; for the underside (ng.y < 0) the slope enters with the opposite sign
    vec3 n = normalize(ng + vec3(-slope.x, 0.0, -slope.y) * sign(ng.y));
    return dot(n, ng) > 0.0 ? n : ng;
}

// Everything a shader needs about a hit point.
struct Surface {
    vec3 pos;          // camera-relative
    vec3 worldPos;     // world-anchored (modulo 1024 blocks) for procedural waves, caustics, and lava
    float t;
    vec3 ng;           // geometric normal facing the incoming ray
    vec3 ns;           // shading normal (normal map), same side as ng
    vec3 albedoTex;    // atlas texel
    vec3 albedo;       // atlas * vertex colour (tint * baked AO), as the raster image
    vec3 albedoLit;    // albedo without the baked vanilla face shade
    vec2 light;        // vanilla (blockLight, skyLight) 0..1
    float roughness;   // linear
    float f0;          // dielectric reflectance
    float metal;       // 0 or 1
    float emission;    // 0..1
    float mapAo;       // LabPBR AO, 1 = none
    vec2 uv;
    uint slot;
    bool translucent;
    float alpha;
    bool isWater;
    bool isLava;
    bool isIce;
    bool isPortal;
    bool isRedstone;
    bool isFoliage;
    bool leafCube;     // foliage that is a face of a full cube (leaf block): one block thick, not a card
    bool isOutline;
    bool backFace;     // the ray left the block through this face (glass: the tint was applied by the entry face)
    vec3 motion;       // previous-frame position minus current (world, blocks); non-zero only for moving entities
};

// Fills s from a committed triangle hit. wantNormal = false skips everything only a shading normal consumes (the normal
// atlas fetch, the tangent basis, the water wave normal): the transmittance walk reads tint, alpha, f0 and the geometric
// normal only, so a shadow ray crossing a pane or a water surface no longer pays for a normal it never looks at.
void fetchSurface(uint slot, uint geom, uint prim, vec2 bary, float t, vec3 origin, vec3 dir, bool wantNormal, out Surface s) {
    s.motion = vec3(0.0);
    s.isOutline = false;
    vec3 b3 = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    Section sec = sections.s[slot];
    uvec3 v = triangleVertices(sec.quadStart[geom] + (prim >> 1u), prim);
    FloatBuf pos = FloatBuf(sec.posAddr);
    vec3 p0 = loadPos(pos, v.x);
    vec3 p1 = loadPos(pos, v.y);
    vec3 p2 = loadPos(pos, v.z);
    vec3 n = normalizeOr(cross(p1 - p0, p2 - p0), -dir);
    FloatBuf uvb = FloatBuf(sec.uvAddr);
    vec2 uv0 = loadUv(uvb, v.x);
    vec2 uv1 = loadUv(uvb, v.y);
    vec2 uv2 = loadUv(uvb, v.z);
    vec2 uv = b3.x * uv0 + b3.y * uv1 + b3.z * uv2;
    UIntBuf cols = UIntBuf(sec.colAddr);
    vec3 vcol = b3.x * unpackColor(cols.v[v.x]) + b3.y * unpackColor(cols.v[v.y]) + b3.z * unpackColor(cols.v[v.z]);
    uint geomPass = (uint(sec.origin.w) >> (2u * geom)) & 3u;
    UIntBuf lights = UIntBuf(sec.lightAddr);
    uint rawLight0 = lights.v[v.x];
    s.light = b3.x * unpackLight(lights.v[v.x]) + b3.y * unpackLight(lights.v[v.y]) + b3.z * unpackLight(lights.v[v.z]);
    vec4 tex = textureLod(atlas, uv, 0.0);
    s.albedoTex = tex.rgb;
    s.alpha = tex.a;
    s.albedo = s.albedoTex * vcol;
    // Lit shading removes the baked vanilla face shade (the hemisphere ambient / traced light takes its place).
    // Clamped to 1.0 so unshaded foliage/cross quads do not blow up, preserving full biome tint hue and saturation.
    s.albedoLit = s.albedoTex * clamp(vcol / max(cardinalShade(n), 0.5), 0.0, 1.0);
    s.uv = uv;
    s.slot = slot;
    s.t = t;
    s.pos = origin + dir * t;
    s.worldPos = vec3(sec.origin.xyz & 1023) + b3.x * p0 + b3.y * p1 + b3.z * p2;
    s.translucent = ((uint(sec.origin.w) >> (2u * geom)) & 3u) == 2u;
    s.backFace = dot(n, dir) > 0.0;
    s.ng = dot(n, dir) > 0.0 ? -n : n;
    s.ns = s.ng;
    s.roughness = s.translucent ? 0.02 : 0.9;
    s.f0 = 0.04;
    s.metal = 0.0;
    s.emission = 0.0;
    s.mapAo = 1.0;
    vec4 m = textureLod(matAtlas, uv, 0.0);
    uint matSourceFlags = 255u - uint(m.a * 255.0 + 0.5);
    bool taggedRedstone = (matSourceFlags & 4u) != 0u;
    bool redstoneColorHeuristic = abs(s.albedoTex.r - s.albedoTex.g) < 0.15 && abs(s.albedoTex.g - s.albedoTex.b) < 0.15
                               && vcol.r > 0.15 && vcol.r > vcol.g * 1.8 && vcol.r > vcol.b * 1.8;
    // The 26.3 dust models are forced into the TRANSLUCENT pass (see MATERIAL_SOURCE_REDSTONE). Tagged dust texels are not glass:
    // treating the pass as translucent made the transparent plate an interface (grey sheet, false mirror, washed-out pink). The
    // tag (material atlas) overrides the pass: texels are cutout (alpha < 0.5 is a hole) and shaded as an emissive solid.
    bool isRedstoneWire = hasFlag(FLAG_MATERIALS) ? taggedRedstone : (!s.translucent && redstoneColorHeuristic);
    float dustCov = 1.0;
    if (isRedstoneWire) {
        s.translucent = false;
        s.roughness = 0.9;
        float sig = clamp((vcol.r - 0.30) / 0.70, 0.0, 1.0);
        if (dustFootprintTexels(t) > 1.0) {
            // far dust: the coverage of the pixel footprint (see dustCoverage) decides, a texel between two dust texels still shows the dust
            dustCov = dustCoverage(uv, t);
            s.alpha = dustCov >= 0.04 ? 1.0 : 0.0;
            if (tex.a < 0.5) {
                s.albedoTex = vec3(0.9);
            }
        }
        s.albedoLit = s.albedoTex * vcol;
        s.emission = sig;
    }
    s.isRedstone = isRedstoneWire;
    if (hasFlag(FLAG_MATERIALS)) {
        s.roughness = m.r;
        s.metal = m.g > 229.5 / 255.0 ? 1.0 : 0.0;
        s.f0 = s.metal > 0.5 ? 0.04 : m.g;
        s.emission = m.b;
        // Redstone wire / dust: emission proportional to signal power (0..15 from vcol.r)
        if (isRedstoneWire) {
            float sig = clamp((vcol.r - 0.30) / 0.70, 0.0, 1.0);
            // far dust fades with its coverage instead of popping texel by texel
            s.emission = sig * (dustCov >= 1.0 ? 1.0 : clamp(0.25 + 2.5 * dustCov, 0.0, 1.0));
        }
        bool labN = (matSourceFlags & 2u) != 0u;   // material alpha bit 1 cleared: LabPBR _n
        if (wantNormal && ((labN && hasFlag(FLAG_NORMAL_MAPS)) || (!labN && hasFlag(FLAG_AUTO_NORMALS)))) {
            vec4 nm = textureLod(nrmAtlas, uv, 0.0);
            s.mapAo = nm.b;
            vec2 xy = (nm.rg - vec2(128.0 / 255.0)) * (255.0 / 127.0);   // DirectX: x right, y down (= +v of the atlas)
            if (!labN) {
                // auto-normal: smooth deadzone on low-contrast noise + scale the slope; keep it a valid unit-disc xy
                xy *= smoothstep(0.01, 0.04, length(xy)) * StatsBuf(pc.statsAddr).autoNormalStrength;
                float l2 = dot(xy, xy);
                if (l2 > 0.81) {
                    xy *= 0.9 * inversesqrt(l2);
                }
            }
            if (dot(xy, xy) > 1.0e-4) {
                vec3 e1 = p1 - p0;
                vec3 e2 = p2 - p0;
                vec2 d1 = uv1 - uv0;
                vec2 d2 = uv2 - uv0;
                float det = d1.x * d2.y - d2.x * d1.y;
                if (abs(det) > 1.0e-12) {
                    float r = 1.0 / det;
                    // det only says the UVs are not collinear. A zero-area triangle (three collinear vertices) with real atlas
                    // UVs still passes the det gate while e1 * d2.y - e2 * d1.y cancels exactly, so the tangents need their own
                    // guard: falling back to the geometric normal collapses the perturbation instead of writing NaN into s.ns.
                    vec3 T = normalizeOr((e1 * d2.y - e2 * d1.y) * r, n);   // dP/du
                    vec3 B = normalizeOr((e2 * d1.x - e1 * d2.x) * r, n);   // dP/dv (down in the texture)
                    float z = sqrt(max(0.0, 1.0 - dot(xy, xy)));
                    vec3 nsObj = normalizeOr(T * xy.x + B * xy.y + n * z, n);
                    vec3 cand = dot(n, s.ng) > 0.0 ? nsObj : reflect(nsObj, n);   // keep it on the side facing the ray
                    float ndot = dot(cand, s.ng);
                    // Smooth blending towards geometric normal to eliminate normal popping and seam tearing
                    s.ns = normalize(mix(s.ng, cand, smoothstep(0.05, 0.35, ndot)));
                }
            }
        }
    }

    if ((!s.translucent && s.alpha < 0.5) || s.alpha < 0.05) {
        s.roughness = 1.0;
        s.metal = 0.0;
        s.f0 = 0.0;
        s.emission = 0.0;
    }
    // Identify water, lava, and ice (including blue_ice)
    s.isIce = (s.translucent && (s.albedo.b > s.albedo.r && s.albedo.b > 0.4 && s.roughness < 0.2 && s.roughness > 0.035))
           || (s.f0 <= 0.025 && s.roughness <= 0.20 && s.roughness > 0.035 && s.metal < 0.5 && s.albedo.b > s.albedo.r * 1.1 && s.albedo.b > 0.35);
    // Water detection: translucent pass, water material f0 <= 0.025 (water f0 is 0.02; glass f0 is 0.04), not ice.
    // Glass (regular and stained) has f0 ~0.04 and is strictly s.isWater = false with flat normal s.ns = s.ng!
    // The alpha of the atlas texel is NOT a criterion: the 26.3 water sprite is opaque (the translucency lives in the render pass), so
    // with `alpha < 0.99` no water was ever recognised and it was shaded as a texel-tinted glass sheet (the blocky 16x16 pattern).
    s.isWater = s.translucent && s.f0 <= 0.025 && s.roughness <= 0.035 && !s.isIce;
    // Lava detection: requires material-atlas emission >= 0.5 (real lava/magma), orange-warm hue
    // (r dominant over both g and b), and never translucent.
    // Intentionally NOT using s.light.x — nearby torches/campfires raised block-light on warm-toned
    // wood/oak-planks/sign-posts above 0.8, falsely classifying them as lava.
    s.isLava = !s.translucent && s.emission >= 0.5
            && s.albedoTex.r > 0.55 && s.albedoTex.r > s.albedoTex.g * 1.05
            && s.albedoTex.g > s.albedoTex.b * 1.5;
    if (s.isLava && s.emission <= 0.0) {
        s.emission = 1.0;
    }
    if (s.isWater) {
        s.roughness = 0.02;
        s.metal = 0.0;
        s.f0 = 0.02;
        if (wantNormal) {
            float footprint = t * WATER_PIXEL_ANGLE / max(abs(dot(dir, s.ng)), 0.06);
            float lostVar;
            vec3 waveN = waterWaveNormal(s.worldPos, s.ng, waterTime(), footprint, lostVar);
            if (getWaterStyle() == WATER_STYLE_REALISTIC) {
                s.ns = waveN;
                s.roughness = clamp(sqrt(lostVar) * 1.6, 0.02, 0.30);
            } else {
                // Vanilla style keeps the wave shape but damps it: clearly visible ripples, not a storm
                s.ns = normalize(mix(s.ng, waveN, 0.55));
                s.roughness = clamp(0.02 + sqrt(lostVar) * 0.8, 0.02, 0.16);
            }
        }
    }
    if (s.isIce) {
        s.roughness = 0.05;
        s.ns = s.ng;
    }
    // Nether portal detection: emissive translucent/semi-transparent surface
    s.isPortal = (s.translucent || s.alpha < 0.99) && s.emission >= 0.5;

    // Foliage classification: the quads of a plant or a leaf block, tagged at BVH build time from the block state. The
    // cutout pass alone is NOT a foliage test: bars, chains, panes, ladders, rails and doors share it, and the leaf term
    // made every back-lit one glow warm and let the one-block shadow start step past a thin pane into a lit cell. Emissive
    // cutout (torches, lanterns, campfires) is excluded too: a flame does not transmit key light. A per-quad bit survives
    // resource packs and modded models, which a name or texture heuristic cannot.
    s.isFoliage = (rawLight0 & VERT_FLAG_VEGETATION) != 0u && geomPass == 1u && s.emission < 0.5 && !s.translucent
        && !s.isRedstone && !s.isLava && !s.isWater && !s.isIce && !s.isPortal;
    // Full leaf cube vs thin card: only the cube's shadow ray has to cross its own cell (see leafShadowOrigin).
    s.leafCube = s.isFoliage && (rawLight0 & VERT_FLAG_LEAF_CUBE) != 0u;
}

// Fills s from a committed entity triangle hit: real triangle (positions, vertex colour, vanilla light), texture from the RT
// entity atlas (EntityTextureAtlas), per-vertex previous position for the motion vectors.
void fetchEntitySurface(uint customIndex, uint geom, uint prim, vec2 bary, float t, vec3 origin, vec3 dir, out Surface s) {
    StatsBuf fb = StatsBuf(pc.statsAddr);
    s.slot = customIndex;
    s.t = t;
    s.pos = origin + dir * t;
    s.worldPos = s.pos + StatsBuf(pc.statsAddr).camRel.xyz;
    s.backFace = false;
    s.motion = vec3(0.0);
    vec3 n = -dir;
    vec3 col = vec3(1.0);
    float alpha = 1.0;
    vec2 lt = vec2(0.5, 1.0);
    vec2 uv = bary;
    float texelEmission = 0.0;
    uint entSlot = 0u;
    bool isEndPortalEnt = false;
    bool isOutline = false;
    if (prim < fb.entityTris) {
        FloatBuf pb = FloatBuf(fb.entPosAddr);
        UIntBuf ab = UIntBuf(fb.entAttrAddr);
        uvec3 vi = entityTriangle(prim);
        entSlot = entitySlot(vi);
        vec3 p0 = vec3(pb.v[vi.x * 3u], pb.v[vi.x * 3u + 1u], pb.v[vi.x * 3u + 2u]);
        vec3 p1 = vec3(pb.v[vi.y * 3u], pb.v[vi.y * 3u + 1u], pb.v[vi.y * 3u + 2u]);
        vec3 p2 = vec3(pb.v[vi.z * 3u], pb.v[vi.z * 3u + 1u], pb.v[vi.z * 3u + 2u]);
        vec3 g = cross(p1 - p0, p2 - p0);
        if (dot(g, g) > 1.0e-20) {
            n = normalize(g);
        }
        vec3 b3 = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
        vec4 c0 = unpackUnorm4x8(ab.v[vi.x * 4u]);
        vec4 c1 = unpackUnorm4x8(ab.v[vi.y * 4u]);
        vec4 c2 = unpackUnorm4x8(ab.v[vi.z * 4u]);
        uint l = ab.v[vi.x * 4u + 1u];
        isOutline = ((l >> 24u) & 0x08u) != 0u;
        isEndPortalEnt = ((l >> 24u) & 0x80u) != 0u;
        lt = clamp(vec2(float(l & 0xFFu), float((l >> 16u) & 0xFFu)) / 240.0, 0.0, 1.0);
        uv = entityUv(vi, b3);
        vec4 tex = entityTexel(entSlot, uv);
        col = tex.rgb * (c0.rgb * b3.x + c1.rgb * b3.y + c2.rgb * b3.z);
        if (isOutline) {
            col = (c0.rgb * b3.x + c1.rgb * b3.y + c2.rgb * b3.z);
            alpha = (c0.a * b3.x + c1.a * b3.y + c2.a * b3.z);
            texelEmission = 0.35;
        } else if (((l >> 24u) & 0x40u) != 0u) {
            col = mix(col, vec3(1.0, 0.05, 0.05), 0.65);
            alpha = tex.a;
            texelEmission = entityEmission(tex);
        } else {
            alpha = tex.a;
            texelEmission = entityEmission(tex);
        }
        if (isEndPortalEnt) {
            // Multi-layer cosmic End Portal starfield with view-parallax depth
            float pt = float(pc.frame) * 0.015;
            vec3 wp = s.worldPos;
            vec3 parallax = dir / max(abs(dir.y), 0.25);
            vec3 portalCol = vec3(0.015, 0.035, 0.065);
            const vec3 layerColors[6] = vec3[6](
                vec3(0.22, 0.58, 0.72),
                vec3(0.45, 0.28, 0.85),
                vec3(0.18, 0.82, 0.75),
                vec3(0.75, 0.42, 0.95),
                vec3(0.35, 0.92, 0.88),
                vec3(0.95, 0.88, 1.00)
            );
            for (int layer = 0; layer < 6; layer++) {
                float fl = float(layer + 1);
                vec2 puv = wp.xz * (1.8 + fl * 0.85) + parallax.xz * (0.12 * fl)
                         + vec2(sin(pt * (0.7 + 0.2 * fl) + fl * 1.7), cos(pt * (0.6 + 0.15 * fl) - fl * 2.3)) * 0.45;
                vec2 cell = floor(puv * 8.0);
                vec2 local = fract(puv * 8.0) - 0.5;
                uint h = uint(int(cell.x) * 73856093 ^ int(cell.y) * 19349663 ^ (layer * 83492791));
                h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u;
                vec2 starOff = (vec2(float(h & 0xFFu), float((h >> 8u) & 0xFFu)) / 255.0 - 0.5) * 0.55;
                float prob = float((h >> 16u) & 0xFFu) / 255.0;
                if (prob > 0.72) {
                    float d2 = dot(local - starOff, local - starOff);
                    float star = exp(-d2 * (90.0 + fl * 25.0)) * (0.6 + 0.4 * sin(pt * 3.0 + float(h & 63u)));
                    portalCol += layerColors[layer] * star * (1.25 - 0.10 * fl);
                }
            }
            col = clamp(portalCol, vec3(0.01), vec3(1.0));
            alpha = 1.0;
            texelEmission = 0.95;
        } else if (lt.x > 0.995 && lt.y > 0.995 && alpha >= 0.1) {
            // vanilla draws this part at full light, unaffected by the surroundings (the item of a glow item frame, blaze, magma
            // cube): it shows its own pixels instead of the scene light
            texelEmission = max(texelEmission, 0.4);
        }
        if (fb.entPrevAddr.x != 0u || fb.entPrevAddr.y != 0u) {
            FloatBuf qb = FloatBuf(fb.entPrevAddr);
            vec3 q0 = vec3(qb.v[vi.x * 3u], qb.v[vi.x * 3u + 1u], qb.v[vi.x * 3u + 2u]);
            vec3 q1 = vec3(qb.v[vi.y * 3u], qb.v[vi.y * 3u + 1u], qb.v[vi.y * 3u + 2u]);
            vec3 q2 = vec3(qb.v[vi.z * 3u], qb.v[vi.z * 3u + 1u], qb.v[vi.z * 3u + 2u]);
            s.motion = q0.x > 1.0e29 ? vec3(1.0e30) : (q0 - p0) * b3.x + (q1 - p1) * b3.y + (q2 - p2) * b3.z;
        }
        if (dot(n, dir) > 0.0) {
            n = -n;   // face the incoming ray (entity models are not consistently wound, culling is off)
        }
        vec3 ng = n;
        vec3 ns = ng;
        // Auto-normal: subtle Sobel gradient over entity atlas luminance, strictly keeping s.ng unperturbed and ignoring transparent UV borders.
        if (!isEndPortalEnt && hasFlag(FLAG_AUTO_NORMALS) && dot(g, g) > 1.0e-20 && alpha >= 0.1) {
            uint slot = entitySlot(vi);
            float lc = dot(tex.rgb, vec3(0.299, 0.587, 0.114));
            float lxn = entityLumOffset(slot, uv, -1, 0);
            float lxp = entityLumOffset(slot, uv,  1, 0);
            float lyn = entityLumOffset(slot, uv,  0,-1);
            float lyp = entityLumOffset(slot, uv,  0, 1);
            if (lxn < 0.0) lxn = lc;
            if (lxp < 0.0) lxp = lc;
            if (lyn < 0.0) lyn = lc;
            if (lyp < 0.0) lyp = lc;
            vec2 grad = vec2(lxn - lxp, lyp - lyn); // du, dv in texture space
            float gradLen = length(grad);
            // Smooth deadzone and gentle strength so mob skin stays organic without harsh embossed seams
            grad *= smoothstep(0.025, 0.08, gradLen) * (0.28 * StatsBuf(pc.statsAddr).autoNormalStrength);
            float gl2 = dot(grad, grad);
            if (gl2 > 0.25) grad *= 0.5 * inversesqrt(gl2);
            if (dot(grad, grad) > 1.0e-4) {
                vec3 e1 = p1 - p0;
                vec3 e2 = p2 - p0;
                vec2 d1 = entityUv(vi, vec3(0.0, 1.0, 0.0)) - entityUv(vi, vec3(1.0, 0.0, 0.0));
                vec2 d2 = entityUv(vi, vec3(0.0, 0.0, 1.0)) - entityUv(vi, vec3(1.0, 0.0, 0.0));
                float det = d1.x * d2.y - d2.x * d1.y;
                if (abs(det) > 1.0e-12) {
                    float rDet = 1.0 / det;
                    vec3 T = normalize((e1 * d2.y - e2 * d1.y) * rDet);
                    vec3 B = normalize((e2 * d1.x - e1 * d2.x) * rDet);
                    T = normalize(T - ng * dot(ng, T));
                    B = normalize(B - ng * dot(ng, B));
                    float z = sqrt(max(0.25, 1.0 - dot(grad, grad)));
                    vec3 nsObj = normalize(T * grad.x + B * grad.y + ng * z);
                    vec3 cand = dot(nsObj, ng) > 0.0 ? nsObj : reflect(nsObj, ng);
                    float ndot = dot(cand, ng);
                    ns = normalize(mix(ng, cand, smoothstep(0.20, 0.65, ndot)));
                }
            }
        }
        s.ng = ng;
        s.ns = ns;
    } else {
        if (dot(n, dir) > 0.0) {
            n = -n;
        }
        s.ng = n;
        s.ns = n;
    }
    // Entity surfaces default to matte dielectric (roughness 0.85). Decodes PBR properties of held items
    // (iron/gold/netherite/copper = metal 1.0; diamond = IOR 2.42, roughness 0.05) packed into entity_tex_rects.
    float roughEst = isEndPortalEnt ? 0.05 : 0.85;
    float metalEst = 0.0;
    float f0Est = 0.04;
    if (!isEndPortalEnt && (fb.entRectAddr.x != 0u || fb.entRectAddr.y != 0u)) {
        UIntBuf rb = UIntBuf(fb.entRectAddr);
        uvec4 r = uvec4(rb.v[entSlot * 4u], rb.v[entSlot * 4u + 1u], rb.v[entSlot * 4u + 2u], rb.v[entSlot * 4u + 3u]);
        if (((r.w >> 24u) & 1u) != 0u) {
            roughEst = float((r.z >> 16u) & 0xFFu) / 255.0;
            metalEst = float((r.z >> 24u) & 0xFFu) / 255.0;
            f0Est = float((r.w >> 16u) & 0xFFu) / 255.0;
        }
    }
    // Tool/sword wooden handle (dark brown texel) remains matte dielectric
    if (metalEst > 0.5 && col.r > col.b * 1.8 && col.r < 0.45 && col.g < 0.30) {
        metalEst = 0.0;
        roughEst = 0.85;
    }
    s.albedoTex = col;
    s.albedo = col;
    s.albedoLit = col;
    // texels of glass-like items (stained glass, ice, slime: alpha 0.1 .. 0.95) keep their alpha and are an interface like the block
    // (black texels are the shadow blob / decals of the entity, not glass: they stay a cutout)
    bool glassy = !isEndPortalEnt && entityTexelTranslucent(vec4(0.0, 0.0, 0.0, alpha)) && max(col.r, max(col.g, col.b)) > 0.08;
    s.alpha = alpha >= 0.1 ? (glassy ? alpha : 1.0) : alpha;
    s.uv = uv;
    s.light = lt;
    s.roughness = roughEst;
    s.metal = metalEst;
    s.f0 = f0Est;
    s.emission = texelEmission;
    s.mapAo = 1.0;
    s.translucent = glassy;
    if (glassy) {
        s.roughness = min(s.roughness, 0.05);   // smooth dielectric like the block it was dropped from
    }
    s.isWater = false;
    s.isIce = false;
    s.isLava = false;
    s.isPortal = isEndPortalEnt;
    s.isRedstone = false;
    s.isFoliage = false;
    s.leafCube = false;
    s.isOutline = isOutline;
}


// Closest hit along a ray; translucent surfaces are hit (opaque) unless skipTranslucent. Returns false on a miss.
// excludeSelfAvatar: primary camera rays only - the first-person body is around the eye (StatsBuf.selfAvatarTri*).
bool traceSurface(vec3 origin, vec3 dir, float tmin, float tmax, bool skipTranslucent, bool excludeSelfAvatar, out Surface s) {
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, tmin, dir, tmax);
    while (rayQueryProceedEXT(rq)) {
        if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }
        if (excludeSelfAvatar && isSelfAvatarPrim(uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false)),
            uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)))) {
            continue;   // the body of the first-person camera: a hit here is its own inside face
        }
        if (CANDIDATE_ACCEPTED(rq, skipTranslucent)) {
            rayQueryConfirmIntersectionEXT(rq);
        }
    }
    if (rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionTriangleEXT) {
        return false;
    }
    uint customIndex = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
    if (isEntityHit(customIndex)) {
        fetchEntitySurface(customIndex,
            uint(rayQueryGetIntersectionGeometryIndexEXT(rq, true)),
            uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)),
            rayQueryGetIntersectionBarycentricsEXT(rq, true), rayQueryGetIntersectionTEXT(rq, true), origin, dir, s);
    } else {
        fetchSurface(customIndex,
            uint(rayQueryGetIntersectionGeometryIndexEXT(rq, true)),
            uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true)),
            rayQueryGetIntersectionBarycentricsEXT(rq, true), rayQueryGetIntersectionTEXT(rq, true), origin, dir, true, s);
    }
    return true;
}

// Secondary, shadow, sky and camera-medium queries: the player's body is real geometry for all of them.
bool traceSurface(vec3 origin, vec3 dir, float tmin, float tmax, bool skipTranslucent, out Surface s) {
    return traceSurface(origin, dir, tmin, tmax, skipTranslucent, false, s);
}

// RGB transmittance along a shadow ray accounting for Beer-Lambert absorption through translucent surfaces (stained glass, water, ice).
// Entities and terrain cast colored shadows with up to 8 translucent layers.
// Light filtering of one translucent layer (stained glass, water, ice, translucent texels) hit by a shadow ray: Beer-Lambert style
// absorption by the tinted texture colour, weighted by the alpha; clear glass (alpha < 0.1) transmits without tint.
vec3 shadowLayerTransmittance(Surface s, vec3 d) {
    if (s.backFace && !s.isWater && !s.isIce) {
        return vec3(1.0 - s.f0);   // exit face: tinted at the entry face
    }
    vec3 c = clamp(s.albedoLit, vec3(0.001), vec3(1.0));
    if (!s.isWater && !s.isIce) {
        // Thin pane: Beer-Lambert absorption by the texel colour with the texel alpha as thickness, no 1/cos grazing term
        // (the slab is thin and vanilla does not darken its shadows towards grazing). Same model as interfaceTransmittance.
        float power = s.alpha < 0.1 ? 0.0 : clamp(s.alpha, 0.0, 1.0);
        return (1.0 - s.f0) * pow(c, vec3(power));
    }
    float cosIncidence = max(abs(dot(s.ng, d)), 0.05);
    float effAlpha = clamp(s.alpha < 0.99 ? s.alpha : 0.65, 0.5, 0.9);
    float power = s.alpha < 0.1 ? 0.0 : max(1.0, effAlpha * 2.0) / cosIncidence;
    return (1.0 - s.f0) * pow(c, vec3(power));
}

// RGB transmittance along a shadow ray in ONE traversal (was: a visibility ray plus up to 8 closest-hit walks, one traversal per
// glass face). Every non-opaque candidate triangle is classified by candidateAccepted(skip = true): an opaque one (solid, cutout
// texel >= 0.99, entity) blocks the light and ends the query; a filter (translucent pass, glass / water / ice texel) is fetched
// once and multiplies the transmittance. The product is order independent and a blocker makes the result 0 whatever order the
// candidates arrive in, so no sorting is needed. Same layer formula as the walk it replaces (shadowLayerTransmittance).
vec3 traceTransmittance(vec3 origin, vec3 d, float tmax) {
    if (tmax <= 0.001) {
        return vec3(1.0);
    }
    rayQueryEXT sq;
    rayQueryInitializeEXT(sq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, 0.001, d, tmax);
    vec3 T = vec3(1.0);
    while (rayQueryProceedEXT(sq)) {
        if (rayQueryGetIntersectionTypeEXT(sq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }
        uint customIndex = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(sq, false));
        uint geom = uint(rayQueryGetIntersectionGeometryIndexEXT(sq, false));
        uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(sq, false));
        vec2 bary = rayQueryGetIntersectionBarycentricsEXT(sq, false);
        gSawTranslucent = false;
        gHitT = rayQueryGetIntersectionTEXT(sq, false);
        if (candidateAccepted(customIndex, geom, prim, bary, true, true)) {
            return vec3(0.0);
        }
        if (!gSawTranslucent) {
            continue;   // a hole of a cutout texel
        }
        Surface s;
        if (isEntityHit(customIndex)) {
            fetchEntitySurface(customIndex, geom, prim, bary, rayQueryGetIntersectionTEXT(sq, false), origin, d, s);
        } else {
            fetchSurface(customIndex, geom, prim, bary, rayQueryGetIntersectionTEXT(sq, false), origin, d, false, s);
        }
        if (s.isFoliage) {
            // Leaves / grass filter the key light instead of blocking it: ~60 % transmission, tinted by the plant. Entry and
            // exit face of one leaf block keep ~36 %, the share the sky term assumes for a canopy (tracedSkyLight canopyTrans).
            // The old opaque cutout made a grass tuft cast hard black texel squares on snow and let no sun under a tree.
            vec3 hue = s.albedoLit / max(max(s.albedoLit.r, max(s.albedoLit.g, s.albedoLit.b)), 1.0e-3);
            T *= 0.60 * mix(vec3(1.0), hue, 0.45);
        } else {
            T *= shadowLayerTransmittance(s, d);
        }
        if (max(T.r, max(T.g, T.b)) < 0.001) {
            return vec3(0.0);
        }
    }
    if (rayQueryGetIntersectionTypeEXT(sq, true) != gl_RayQueryCommittedIntersectionNoneEXT) {
        return vec3(0.0);
    }
    return T;
}

// Start of the shadow ray that lets key light through a leaf. The shaded point of a plant lies on the camera side of the card,
// so with the sun behind it the ray would begin on the far side and hit the card itself. A card (cross model, vine, kelp,
// cane, bamboo) is zero thickness and 1 cm along the sun already clears it; a leaf BLOCK presents one face of a full cube, whose
// sun-side face lies exactly 1 block along -ng whichever way the sun leans. For the cube the step therefore goes along -ng, not
// along L: pushing 1/leafBack along L (capped at 1.75) left the origin inside the block at an incidence above ~55 degrees, where
// leafBack < 0.57, and the block's own far face blocked the ray again. Which of the two it is comes from the block tag
// (Surface.leafCube), never from an axis-aligned normal: a wall vine has one too, and stepping a block through it started the
// ray inside the wall. A back-face hit is the exception: the ray already reached the sun-side face through a cutout hole, so that
// point is outside the block towards the sun and only needs the thickness step. Only the caster cell is skipped, so an occluder
// still shadows the leaf.
vec3 leafShadowOrigin(Surface s, vec3 L) {
    return s.leafCube && !s.backFace ? s.pos - s.ng * 1.01 : s.pos + L * 0.01;
}

// Display colour of a glowing texel: texel colour x emission mask, hue preserving. The brightest channel goes through a soft
// shoulder (max 0.96, slope ~2 at the dark end), the other channels are scaled with it, so a glowstone gradient, a flame or a
// lamp keeps the colours of its own pixels instead of clipping to white; dark pixels (mask 0) stay dark.
vec3 emissionDisplay(vec3 texel, float emission) {
    vec3 c = texel * emission;
    float m = max(c.r, max(c.g, c.b));
    if (m <= 1.0e-4) {
        return vec3(0.0);
    }
    float shoulder = 0.96 * (1.0 - exp(-2.2 * m));
    return c * (shoulder / m);
}

vec3 emitterLightColor(vec3 tex);

// Radiance emitted by a surface (only with emissive lights enabled and baked materials). Built in display space and
// converted with the inverse of the tone mapping (like the sky), so the camera sees emissionDisplay() whatever the exposure.
vec3 emitted(Surface s) {
    if (!hasFlag(FLAG_EMISSIVE) || s.emission <= 0.0) {
        return vec3(0.0);
    }
    if (s.isLava) {
        float t = float(pc.frame) * 0.06;
        vec3 p = s.worldPos;
        float turb = sin(p.x * 3.0 + t) * cos(p.z * 3.0 - t * 0.8)
                   + 0.5 * sin(p.x * 6.0 - t * 1.4 + p.y * 4.0) * cos(p.z * 6.0 + t * 1.2)
                   + 0.25 * sin((p.x + p.z) * 12.0 + t * 2.2);
        float pulse = clamp(1.0 + 0.35 * turb, 0.6, 1.8);
        float lum = dot(s.albedoTex, vec3(0.299, 0.587, 0.114));
        float texelHotspot = 0.8 + 1.4 * pow(clamp(lum, 0.0, 1.0), 1.6);
        vec3 fieryColor = mix(s.albedoTex * vec3(1.35, 1.08, 0.65), vec3(1.0, 0.86, 0.36), pow(clamp(lum, 0.0, 1.0), 2.0) * 0.65);
        return displayToRadiance(emissionDisplay(fieryColor, 1.15 * pulse * texelHotspot * max(s.emission, 1.0)));
    }
    // Redstone wire emits saturated red light even if the atlas texture is greyscale
    if (s.isRedstone && s.emission > 0.01) {
        // per pixel: the greyscale dust sprite modulates the glow (bright centre pixels glow more), the level follows signal / 15
        float texLum = clamp(dot(s.albedoTex, vec3(0.299, 0.587, 0.114)) * 1.5, 0.0, 1.0);
        vec3 redColor = vec3(1.0, 0.04, 0.02) * (0.8 + 0.4 * s.emission) * mix(0.6, 1.0, texLum);
        return displayToRadiance(emissionDisplay(redColor, s.emission * 1.2));
    }
    vec3 col = emitterLightColor(s.albedoTex);
    return displayToRadiance(emissionDisplay(col, s.emission));
}

const uint NEE_CANDIDATES = 8u;
const float EMITTER_RADIUS = 24.0;

// Categorized and chromatic emission: classifies emitters by spectrum (warm flame, soul fire, redstone,
// sea lantern, glowstone, amethyst/portal, end rod, bioluminescent) so illumination matches real RTX light sources.
vec3 emitterLightColor(vec3 tex) {
    float m = max(tex.r, max(tex.g, tex.b));
    if (m <= 1.0e-4) {
        return vec3(0.0);
    }
    vec3 c = tex / m;

    // Category 1: Pure Redstone (Torch, wire, lamp, repeater, ore)
    if (c.r > 0.65 && c.g < 0.22 && c.b < 0.22) {
        return vec3(1.0, 0.05, 0.02) * mix(m, 1.0, 0.35);
    }

    // Category 2: Warm Flames & Firelight (Torch, Campfire, Lantern, Lava, Fire, Candle)
    // Rich, soft, golden-amber 2200-2400K illumination matching RTX screenshots
    if (c.r >= 0.55 && c.r > c.b * 1.25 && c.g >= 0.18) {
        float warmFactor = clamp((c.g - 0.20) / 0.60, 0.0, 1.0);
        vec3 warmLight = mix(vec3(1.0, 0.52, 0.10), vec3(1.0, 0.76, 0.26), warmFactor);
        return warmLight * 1.35 * mix(m, 1.0, 0.45);
    }

    // Category 3: Soul Fire (Soul Torch, Soul Lantern, Soul Campfire, Soul Fire)
    if (c.b > 0.45 && c.g > 0.35 && c.r < 0.45) {
        return vec3(0.18, 0.85, 1.0) * 1.2 * mix(m, 1.0, 0.4);
    }

    // Category 4: Sea Lantern & Conduit (Pale Aquamarine / Cyan-White)
    if (c.b > 0.65 && c.g > 0.65 && c.r > 0.35 && c.b > c.r * 1.05) {
        return vec3(0.75, 0.94, 1.0) * 1.15 * mix(m, 1.0, 0.4);
    }

    // Category 5: Glowstone, Shroomlight, Ochre Froglight (Warm golden daylight)
    if (c.r > 0.7 && c.g > 0.65 && c.b < 0.45) {
        return vec3(1.0, 0.85, 0.42) * 1.2 * mix(m, 1.0, 0.4);
    }

    // Category 6: Amethyst, Crying Obsidian, Nether Portal (Mystic Violet / Magenta)
    if (c.r > 0.45 && c.b > 0.55 && c.g < 0.45) {
        return vec3(0.85, 0.30, 1.0) * 1.2 * mix(m, 1.0, 0.4);
    }

    // Category 7: End Rod & Purpur (Celestial Lilac White)
    if (c.r > 0.6 && c.b > 0.75 && c.g >= 0.45) {
        return vec3(0.92, 0.85, 1.0) * 1.25 * mix(m, 1.0, 0.4);
    }

    // Category 8: Bioluminescence (Glow Lichen, Cave Vines, Glow Berries, Glow Squid)
    if (c.g > 0.65 && c.b > 0.35 && c.r < 0.5) {
        return vec3(0.45, 1.0, 0.75) * 1.15 * mix(m, 1.0, 0.4);
    }

    // Default: retain vibrant chromaticity without severe desaturation
    vec3 hue = mix(c, vec3(1.0), 0.08);
    return hue * mix(m, 1.0, 0.5);
}
const float INV_PI = 0.31830988618;

// Physically-Based GGX / Trowbridge-Reitz Microfacet Distribution
float distributionGGX(float NdotH, float roughness) {
    float a = roughness * roughness;
    float a2 = max(a * a, 1.0e-5);
    float NdotH2 = NdotH * NdotH;
    float denom = NdotH2 * (a2 - 1.0) + 1.0;
    return a2 / (PI * denom * denom);
}

// Smith lambda of the GGX distribution (Heitz 2014): Lambda(c) = (sqrt(1 + alpha^2 tan^2) - 1) / 2.
// alphaSq is alpha * alpha with alpha = perceptual roughness^2, the same mapping distributionGGX uses.
float smithLambda(float cosTheta, float alphaSq) {
    float c2 = max(cosTheta * cosTheta, 1.0e-8);
    float tan2 = max(1.0 - c2, 0.0) / c2;
    return 0.5 * (sqrt(1.0 + alphaSq * tan2) - 1.0);
}

// Smith Joint Masking-Shadowing Function G2 (Heitz 2014), height correlated: G2 = 1 / (1 + Lambda(V) + Lambda(L)).
float geometrySmith(float NdotV, float NdotL, float roughness) {
    // same floor as distributionGGX (a2 >= 1e-5), so D and G describe the same microsurface at grazing roughness
    float alpha = max(roughness * roughness, 0.0031622777);
    float alphaSq = alpha * alpha;
    return 1.0 / (1.0 + smithLambda(max(NdotV, 0.0), alphaSq) + smithLambda(max(NdotL, 0.0), alphaSq));
}

// G2 / G1 for a reflected sample. The VNDF pdf of a reflected direction is p_L = D * G1(V) / (4 * N.V), so
// BRDF * cosine / pdf collapses to F(V.H) * G2 / G1: D and the pdf cancel exactly.
float smithG2OverG1(float cosV, float cosL, float alphaSq) {
    float lv = smithLambda(max(cosV, 0.0), alphaSq);
    float ll = smithLambda(max(cosL, 0.0), alphaSq);
    return (1.0 + lv) / (1.0 + lv + ll);
}

// Visible-normal GGX sampling (Heitz 2018, "Sampling the GGX Distribution of Visible Normals"): the microfacet normal H
// around n for the view direction v, alpha = perceptual roughness^2. u is a 2D uniform sample in [0,1)^2.
vec3 sampleGgxVndf(vec3 n, vec3 v, float alpha, vec2 u, out float vdotH) {
    vec3 t;
    vec3 b;
    basisAround(n, t, b);
    vec3 vLocal = vec3(dot(v, t), dot(v, b), dot(v, n));
    vec3 vh = normalize(vec3(alpha * vLocal.xy, max(vLocal.z, 1.0e-6)));
    float lensq = vh.x * vh.x + vh.y * vh.y;
    vec3 t1 = lensq > 1.0e-12 ? vec3(-vh.y, vh.x, 0.0) * inversesqrt(lensq) : vec3(1.0, 0.0, 0.0);
    vec3 t2 = cross(vh, t1);
    float r = sqrt(u.x);
    float phi = 2.0 * PI * u.y;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float s = 0.5 * (1.0 + vh.z);
    p2 = (1.0 - s) * sqrt(max(0.0, 1.0 - p1 * p1)) + s * p2;
    vec3 nh = p1 * t1 + p2 * t2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * vh;
    vec3 h = normalize(vec3(alpha * nh.xy, max(nh.z, 1.0e-6)));
    vdotH = dot(vLocal, h);
    return normalize(t * h.x + b * h.y + n * h.z);
}

vec3 brdfSpecularGGX(vec3 N, vec3 V, vec3 L, float roughness, vec3 F0) {
    vec3 H = normalizeOr(V + L, N);
    float NdotL = max(dot(N, L), 0.0);
    float NdotV = max(dot(N, V), 1.0e-4);
    float NdotH = max(dot(N, H), 0.0);
    float VdotH = max(dot(V, H), 0.0);
    if (NdotL <= 0.0) return vec3(0.0);
    
    float D = distributionGGX(NdotH, roughness);
    float G = geometrySmith(NdotV, NdotL, roughness);
    vec3 F = fresnelSchlick(F0, VdotH);
    
    vec3 numerator = D * G * F;
    float denominator = 4.0 * NdotV * NdotL;
    return numerator / max(denominator, 1.0e-4);
}

