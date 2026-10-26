// ---- Thin dielectric interfaces: glass, stained glass, water surface, ice -----------------------------------------------
// A texel with alpha < 0.99 is a thin slab: Schlick reflectance with the physical material F0 (clamped to 0.02..0.08 so a
// missing material cannot make it a mirror), transmission (1 - F) without a refraction offset (panes and block faces are
// thin; water keeps its straight view as in vanilla), absorption by the tinted texture colour weighted by its alpha (stained
// glass and water tint, the clear centre of vanilla glass passes unchanged). Alpha is absorption here, not stochastic coverage:
// coverage sampling made every stained-glass pixel randomly opaque or transparent each frame (the user-visible glass noise).
bool isInterface(Surface s) {
    if (isEntityHit(s.slot)) {
        return false;
    }
    // Nether portal is an emissive volume/quad, not a thin dielectric glass sheet
    if (s.isPortal || s.emission > 0.1) {
        return false;
    }
    // Transparent cutout holes (alpha < 0.5 on solid/cutout geometry) are empty space, not a dielectric interface
    if (surfaceIsHole(s)) {
        return false;
    }
    return s.opticalKind == 1u || s.translucent || s.isIce || (s.f0 <= 0.08 && s.roughness <= 0.15 && s.alpha < 0.99);
}

// Water the realistic style models as a volume (absorption, in-scattering, Snell refraction, caustics); the vanilla style keeps
// the thin straight-through sheet.
bool waterVolumeStyle() {
    return getWaterStyle() == WATER_STYLE_REALISTIC;
}

// Vanilla style: the water is the (animated) water_still / water_flow sprite tinted with the biome colour, drawn over the scene at
// the vanilla translucency.
const float VANILLA_WATER_ALPHA = 0.24;

// Lit colour of the textured vanilla sheet: sky ambient, sun diffuse, sun specular glint trail across the water surface, and block
// light. The block light is the traced emitter estimate, the same one the rest of the scene is lit by: a torch on the bank lights
// the water it actually reaches and whatever stands between the flame and the pool still casts its shadow. The baked light level
// (a 15-block voxel field with no direction) stays for a frame without an emitter list and for the underside of the sheet.
vec3 vanillaWaterSurface(Surface s, vec3 dir, float reach, inout uint seed) {
    vec3 L = pc.keyDir.xyz;
    float sunVis = smoothstep(0.3, 0.9, s.light.y);
    vec3 blockLight = blockLightColor() * s.light.x;
    // s.ng is flipped onto the incoming ray, so an underwater camera looking up at the sheet gets a downward normal and every
    // emitter above the water lies behind the receiver hemisphere: the NEE would return exactly 0 and replace a lit surface with
    // ambient only. The traced estimate therefore takes over the up-facing sheet, the baked field keeps the rest.
    if (hasFlag(FLAG_EMISSIVE) && emitterCount() > 0u && s.ng.y > 0.5) {
        blockLight = sampleEmitters(s, vec3(1.0), reach, seed);   // albedo comes from s.albedoLit * light below
    }
    vec3 light = hemisphereAmbient(1.0, pc.keyDir.w) * max(s.light.y, 0.03)
               + pc.keyColor.rgb * (max(dot(s.ns, L), 0.0) * sunVis)
               + blockLight;
    // Sun specular reflection trail across the water surface towards the camera (RenderDragon / RTX style).
    // L + V is the zero vector when the camera looks exactly along the key light: normalize of it was 0/0, and the resulting NaN
    // spread over the pixel and its temporal history. That view is the specular peak, so the shading normal stands in for H.
    vec3 V = -dir;
    vec3 Hv = L + V;
    float hl2 = dot(Hv, Hv);
    vec3 H = hl2 > 1.0e-6 ? Hv * inversesqrt(hl2) : s.ns;
    float NdotH = max(dot(s.ns, H), 0.0);
    float spec = (pow(NdotH, 128.0) * 2.4 + pow(NdotH, 24.0) * 0.5) * sunVis;
    vec3 sunGlint = pc.keyColor.rgb * spec;
    return s.albedoLit * light + sunGlint;
}

// A horizontal water face; only those separate air from the water body in a way that is tracked (flowing sides stay thin sheets).
bool isWaterBoundary(Surface s) {
    return s.isWater && abs(s.ng.y) > 0.5;
}

float interfaceReflectance(Surface s, vec3 d) {
    float cosV = clamp(abs(dot(d, s.ns)), 0.0, 1.0);
    if (s.optics.x >= 1.0 && s.optics.x <= 1.00001) return 0.0;
    if (s.isWater && waterVolumeStyle() && isWaterBoundary(s)) {
        // exact dielectric Fresnel; from below (ray going up) with total internal reflection beyond the 48 degree Snell window
        float ior = surfaceIor(s);
        return fresnelDielectric(cosV, d.y < 0.0 ? ior : 1.0 / ior);
    }
    if (s.optics.x >= 1.0) return fresnelDielectric(cosV, s.optics.x);
    float f0 = clamp(s.f0, 0.02, 0.08);
    float R = f0 + (1.0 - f0) * pow(1.0 - cosV, 5.0);
    if (!s.isWater && !s.isIce) {
        R = min(R, 0.60);
    }
    return R;
}

vec3 interfaceTransmittance(Surface s, float F, vec3 d) {
    // Beer-Lambert style absorption: colour^thickness, the glass tint is the texel colour and its alpha the thickness (ice 1.3).
    // Legacy clear centres have no dye; authored profiles also apply reference absorption and Fresnel. Stained glass filters
    // by its own dye colour, water (alpha ~0.7,
    // grey texture * biome tint from the vertex colour) keeps its biome blue.
    // Ice / blue_ice transmits tinted with its ice albedo.
    if (s.isWater && waterVolumeStyle() && isWaterBoundary(s)) {
        return vec3(1.0 - F);   // the colour of the water comes from the depth absorption of the medium (waterSegment)
    }
    if (s.isWater) {
        // Not named `filter`: that is a reserved word in GLSL and fails the whole shader.
        vec3 tint = s.optics.x >= 1.0 ? pow(clamp(s.opticalTransmittance, vec3(0.0001), vec3(1.0)),
            vec3(s.optics.y / max(s.optics.z, 0.0001))) : vec3(1.0);
        return (1.0 - F) * (1.0 - VANILLA_WATER_ALPHA) * tint;
    }
    if (s.backFace && !s.isWater && !s.isIce) {
        // exit face of a glass block: the entry face already filtered the light (a block is one slab of coloured glass, not two)
        return vec3((1.0 - F) * (1.0 - s.optics.w * s.alpha));
    }
    if (s.optics.x >= 1.0) return opticalLayerTransmittance(s, F, d);
    vec3 c = clamp(s.albedoLit, vec3(0.001), vec3(1.0));
    if (s.isWater && waterVolumeStyle()) {
        // Realistic style: water is a continuous fluid, filtered by the biome tint rather than 16x16 texture pixel noise
        // which creates blocky texel patterns under realistic waves.
        vec3 tint = s.albedoLit / max(s.albedoTex, vec3(0.05));
        c = clamp(tint * 0.85, vec3(0.001), vec3(1.0));
    } else if (s.isWater) {
        // Vanilla style: the vanilla water sprite (water_still / water_flow of the live, animated block atlas) x biome tint is the
        // look of this style, as in the game; c stays the texel colour s.albedoLit
    }
    // Glass and ice: Beer-Lambert absorption by the texel colour, the texel alpha is the optical thickness. No hue remap: the
    // texel already is the dye colour. The real 26.1 stained glass is (153,51,51) / (51,76,178) at alpha 0.4, so two blue panes
    // keep about (0.25, 0.35, 0.69) and the room behind stays readable; the old hue saturation (normC^2.2, exponent 2 alpha)
    // left ~1 % of red through two panes and turned the room into black / blue voids (owner screenshot 37, PT Quality).
    float effAlpha = s.isIce ? clamp(s.alpha < 0.99 ? s.alpha : 0.65, 0.0, 1.0) : clamp(s.alpha, 0.0, 1.0);
    float power = s.alpha < 0.1 ? 0.0 : (s.isIce ? 1.3 : effAlpha);
    return (1.0 - F) * pow(c, vec3(power));
}

vec3 interfaceTransmittance(Surface s, float F) { return interfaceTransmittance(s, F, s.ng); }

vec3 interfaceDiffuseLayer(Surface s, float F) {
    if (s.optics.w <= 0.0 || s.isWater) return vec3(0.0);
    vec3 light = hemisphereAmbient(1.0, pc.keyDir.w) * max(s.light.y, 0.03) + blockLightColor() * s.light.x;
    return (1.0 - F) * s.optics.w * s.alpha * s.albedoLit * light;
}

// ---- water medium -----------------------------------------------------------------------------------------------------------
// A ray (camera, reflection, bounce) that crosses a horizontal water face toggles the medium. Inside, every segment is attenuated
// by exp(-sigmaT * length) and adds the light it scatters towards the eye, so the colour of water depends on the depth of the
// column instead of a fixed tint. The medium is split into absorption (clear water absorbs red first, the biome tint adds a dye:
// swamp green, warm ocean turquoise) and weak grey scattering that gives deep water a body. Scattering is not optional: with
// absorption alone there is nothing to see once the bottom is attenuated away, which is what made deep oceans black.
struct WaterMedium {
    bool inside;
    float ior;
    vec3 sigmaT;        // extinction (absorption + scattering), 1/block
    vec3 scatterAlbedo; // sigmaS / sigmaT: colour and share of the in-scattered light
    float surfY;        // camera-relative world Y of the water surface
};

WaterMedium noWater() {
    WaterMedium m;
    m.inside = false;
    m.ior = 1.333;
    m.sigmaT = vec3(0.0);
    m.scatterAlbedo = vec3(0.0);
    m.surfY = 0.0;
    return m;
}

const vec3 WATER_CLEAR_ABSORPTION = vec3(0.17, 0.036, 0.013);   // 1/block, red absorbed first, blue last
const float WATER_SCATTERING = 0.055;                           // 1/block, nearly grey
const float WATER_MULTIPLE_SCATTER_FLOOR = 0.65;                // share of the sky that reaches any depth (deep water keeps a body)

// Extinction of the water body for a biome tint (the vertex colour with the baked face shade undone).
vec3 waterSigmaT(vec3 tintIn) {
    vec3 tint = clamp(tintIn, vec3(0.02), vec3(1.0));
    vec3 dye = clamp(0.05 * (-log(tint)), vec3(0.0), vec3(0.20));
    return WATER_CLEAR_ABSORPTION + dye + vec3(WATER_SCATTERING);
}

vec3 waterScatterAlbedo(vec3 tintIn) {
    return vec3(WATER_SCATTERING) / waterSigmaT(tintIn);
}

vec3 waterTint(Surface s) {
    return clamp(s.albedoLit / max(s.albedoTex, vec3(0.05)), vec3(0.02), vec3(1.0));
}

WaterMedium enterWater(Surface s) {
    WaterMedium m;
    m.inside = true;
    m.ior = surfaceIor(s);
    vec3 tint = waterTint(s);
    m.sigmaT = waterSigmaT(tint);
    if (s.optics.x >= 1.0) m.sigmaT += -log(clamp(s.opticalTransmittance, vec3(0.0001), vec3(1.0))) / max(s.optics.z, 0.0001);
    m.scatterAlbedo = vec3(WATER_SCATTERING) / m.sigmaT;
    m.surfY = s.pos.y;
    return m;
}

// Medium of a ray that crosses the boundary s: water entered when going down, left when going up (horizontal faces only).
void crossWaterBoundary(inout WaterMedium m, Surface s, vec3 d) {
    if (!isWaterBoundary(s)) {
        return;
    }
    if (d.y < 0.0 && !m.inside) {
        m = enterWater(s);
    } else if (d.y > 0.0 && m.inside) {
        m.inside = false;
    }
}

// Segment o -> o + d * len inside the medium: transmittance T and in-scattered radiance ins (to be multiplied by the throughput).
// ins = (sigmaS / sigmaT) * L_down * (1 - exp(-sigmaT * len)): the radiance the segment itself scatters towards the eye.
// L_down is the sky dome plus a share of the sun, attenuated by the column above the sample, with a multiple-scattering floor
// so deep water keeps a deep blue instead of fading to black.
void waterSegment(WaterMedium m, vec3 o, vec3 d, float len, out vec3 T, out vec3 ins) {
    T = exp(-m.sigmaT * len);
    float depth = max(m.surfY - (o.y + d.y * len * 0.5), 0.0);
    vec3 down = hemisphereAmbient(1.0, pc.keyDir.w) + pc.keyColor.rgb * (0.35 * max(pc.keyDir.y, 0.0));
    vec3 column = WATER_MULTIPLE_SCATTER_FLOOR
        + (1.0 - WATER_MULTIPLE_SCATTER_FLOOR) * exp(-m.sigmaT * depth * 0.65);
    ins = down * m.scatterAlbedo * column * (vec3(1.0) - T);
}

// Light-weight closest hit for the water queries (camera medium, refracted sun ray): same traversal and alpha test as traceSurface,
// but only the triangle, the material of the hit texel and the vertex colour are read (fetchSurface decodes ~15 more attributes and
// is inlined at every call site, which made the whole shader slower even in scenes without water).
// Returns 0 = miss, 1 = water boundary face (horizontal water; tint = biome tint of the water), 2 = blocker (solid, entity, cutout),
// 3 = other translucent layer (glass, ice, stained glass: the caller lets the ray pass).
uint traceWaterBoundary(vec3 origin, vec3 dir, float tmax, bool excludeSelfAvatar, out float hitT, out vec3 tint, out vec4 optical) {
    rayQueryEXT rq;
    rayQueryInitializeEXT(rq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, 0.001, dir, tmax);
    while (rayQueryProceedEXT(rq)) {
        if (rayQueryGetIntersectionTypeEXT(rq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }
        if (excludeSelfAvatar && isSelfAvatarPrim(uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false)),
            uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, false)))) {
            continue;   // the first-person body: straight up from the eye this is the player's own head
        }
        if (CANDIDATE_ACCEPTED(rq, false)) {
            rayQueryConfirmIntersectionEXT(rq);
        }
    }
    hitT = 0.0;
    tint = vec3(1.0);
    optical = vec4(0.0, 0.0, 0.0, 1.333);
    if (rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionTriangleEXT) {
        return 0u;
    }
    hitT = rayQueryGetIntersectionTEXT(rq, true);
    uint customIndex = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true));
    if (isEntityHit(customIndex)) {
        return 2u;
    }
    uint geom = uint(rayQueryGetIntersectionGeometryIndexEXT(rq, true));
    uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(rq, true));
    Section sec = sections.s[customIndex];
    uint pass = (uint(sec.origin.w) >> (2u * geom)) & 3u;
    if (pass == 0u) {
        return 2u;
    }
    vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
    vec3 b3 = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    uvec3 v = triangleVertices(sec.quadStart[geom] + (prim >> 1u), prim);
    FloatBuf pos = FloatBuf(sec.posAddr);
    vec3 p0 = loadPos(pos, v.x);
    vec3 p1 = loadPos(pos, v.y);
    vec3 p2 = loadPos(pos, v.z);
    vec3 g = cross(p1 - p0, p2 - p0);
    float g2 = dot(g, g);
    if (g2 < 1.0e-12) {
        return 3u;   // zero-area triangle: nothing to classify, and a NaN normal below would pass the water test
    }
    vec3 n = g * inversesqrt(g2);
    FloatBuf uvb = FloatBuf(sec.uvAddr);
    vec2 uv = b3.x * loadUv(uvb, v.x) + b3.y * loadUv(uvb, v.y) + b3.z * loadUv(uvb, v.z);
    vec4 m = textureLod(matAtlas, uv, 0.0);
    // water material of the auto PBR (rough 0.02, f0 0.02), see fetchSurface; ice has roughness 0.05
    uint profile = (255u - uint(m.a * 255.0 + 0.5)) >> 3u;
    float kind = opticalValue(profile, 7u);
    if (pass != 2u && kind == 0.0) return 2u;
    if ((kind != 2.0 && (kind != 0.0 || !(m.g <= 0.025 && m.r <= 0.035))) || abs(n.y) < 0.5) {
        return 3u;
    }
    if (kind == 2.0) {
        optical.w = opticalValue(profile, 0u);
        optical.xyz = -log(clamp(vec3(opticalValue(profile, 4u), opticalValue(profile, 5u), opticalValue(profile, 6u)),
            vec3(0.0001), vec3(1.0))) / max(opticalValue(profile, 2u), 0.0001);
    }
    UIntBuf cols = UIntBuf(sec.colAddr);
    vec3 vcol = b3.x * unpackColor(cols.v[v.x]) + b3.y * unpackColor(cols.v[v.y]) + b3.z * unpackColor(cols.v[v.z]);
    tint = clamp(vcol / max(cardinalShade(n), 0.5), vec3(0.0), vec3(1.0));
    return 1u;
}

// Medium of the camera: a ray straight up from the eye ends on a water face when the camera is under water.
WaterMedium cameraWaterMedium(vec3 origin) {
    WaterMedium m = noWater();
    vec3 o = origin;
    for (uint i = 0u; i < 16u; i++) {
        float t;
        vec3 tint;
        vec4 optical;
        uint k = traceWaterBoundary(o, vec3(0.0, 1.0, 0.0), 160.0, true, t, tint, optical);
        if (k == 3u) {
            o += vec3(0.0, t + 0.002, 0.0);   // glass / ice above the eye: look through it
            continue;
        }
        if (k == 1u) {
            m.inside = true;
            m.ior = optical.w;
            m.sigmaT = waterSigmaT(tint) + optical.xyz;
            m.scatterAlbedo = vec3(WATER_SCATTERING) / m.sigmaT;
            // t starts at o, which may already have advanced past glass or ice.
            m.surfY = o.y + t;
        }
        return m;
    }
    return m;
}

// The camera origin is the same for every pixel, so one lane per subgroup asks the scene and broadcasts the answer (one ray per
// 32 pixels instead of one per pixel). Call it where every lane of the subgroup is active (top of main, before the bounds return).
WaterMedium cameraWaterMediumShared(vec3 origin) {
    WaterMedium m = noWater();
    if (subgroupElect()) {
        m = cameraWaterMedium(origin);
    }
    m.inside = subgroupBroadcastFirst(m.inside);
    m.ior = subgroupBroadcastFirst(m.ior);
    m.sigmaT = subgroupBroadcastFirst(m.sigmaT);
    m.scatterAlbedo = subgroupBroadcastFirst(m.scatterAlbedo);
    m.surfY = subgroupBroadcastFirst(m.surfY);
    return m;
}

// First non-interface surface along a ray; interfaces on the way only attenuate (deterministic, no random choice).
// Uses the same TLAS traversal as traceSurface (mask 0x03: MASK_WORLD | MASK_ENTITIES) so reflections and refractions see
// entities and terrain. The medium m (water body the ray starts in, if any) is updated at every water boundary; filterOut
// collects the transmittance of interfaces and of the water, inscatter the light scattered towards the ray inside the water
// (already weighted by the filter of the part of the ray before it; the caller multiplies it with its own throughput only).
//
// Keep only compact metadata for the nearest interfaces. Vulkan does not order candidate intersections by distance. Delaying
// optical work until traversal finishes prevents panes behind an opaque hit from tinting it, and makes water entry/exit and
// camera reflection weights independent of driver traversal order. The fixed list bounds per-ray storage and sorting work.
const uint INTERFACE_LAYER_CAPACITY = 16u;
struct InterfaceCandidate {
    uvec3 ids; // instance custom index, geometry index, primitive index
    vec2 bary;
    float t;
};

void rememberInterfaceCandidate(inout InterfaceCandidate candidates[INTERFACE_LAYER_CAPACITY], inout uint count,
    inout float overflowT, InterfaceCandidate candidate) {
    uint at = min(count, INTERFACE_LAYER_CAPACITY - 1u);
    if (count == INTERFACE_LAYER_CAPACITY) {
        transparencyOverflow();
        if (candidate.t >= candidates[at].t) {
            overflowT = min(overflowT, candidate.t);
            return;
        }
        overflowT = min(overflowT, candidates[at].t);
    } else {
        count++;
    }
    while (at > 0u) {
        if (candidates[at - 1u].t <= candidate.t) {
            break;
        }
        candidates[at] = candidates[at - 1u];
        at--;
    }
    candidates[at] = candidate;
}

void fetchInterfaceCandidate(InterfaceCandidate candidate, vec3 origin, vec3 dir, out Surface s) {
    if (isEntityHit(candidate.ids.x)) {
        fetchEntitySurface(candidate.ids.x, candidate.ids.y, candidate.ids.z, candidate.bary, candidate.t, origin, dir, s);
    } else {
        fetchSurface(candidate.ids.x, candidate.ids.y, candidate.ids.z, candidate.bary, candidate.t, origin, dir, true, s);
    }
}

// ONE traversal for the whole ray, with the optical layers applied only after the nearest terminal hit is known. A glass
// block has two faces; retaining one query avoids restarting a full closest-hit traversal per face. Only terminals are
// committed, and traversal continues after confirmation so a nearer opaque surface can replace an earlier candidate.
bool traceThroughInterfacesM(vec3 origin, vec3 dir, float reach, inout WaterMedium m, out Surface hit, out vec3 filterOut, out vec3 inscatter) {
    filterOut = vec3(1.0);
    inscatter = vec3(0.0);
    if (reach <= 0.001) {
        return false;
    }
    InterfaceCandidate candidates[INTERFACE_LAYER_CAPACITY];
    uint count = 0u;
    float overflowT = reach;
    rayQueryEXT sq;
    rayQueryInitializeEXT(sq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, 0.001, dir, reach);
    transparencyQuery(false);
    while (rayQueryProceedEXT(sq)) {
        if (rayQueryGetIntersectionTypeEXT(sq, false) != gl_RayQueryCandidateIntersectionTriangleEXT) {
            continue;
        }
        uint slot = uint(rayQueryGetIntersectionInstanceCustomIndexEXT(sq, false));
        uint geom = uint(rayQueryGetIntersectionGeometryIndexEXT(sq, false));
        uint prim = uint(rayQueryGetIntersectionPrimitiveIndexEXT(sq, false));
        vec2 bary = rayQueryGetIntersectionBarycentricsEXT(sq, false);
        float t = rayQueryGetIntersectionTEXT(sq, false);
        gSawTranslucent = false;
        gSawDust = false;
        gHitT = t;
        bool opaque = candidateAccepted(slot, geom, prim, bary, true, false);
        if (!opaque && !gSawTranslucent && !gSawDust) {
            continue;   // hole of a cutout texel, or an overlay a filter ray ignores at all
        }
        if (opaque) {
            rayQueryConfirmIntersectionEXT(sq);
            continue;
        }
        Surface c;
        if (isEntityHit(slot)) {
            fetchEntitySurface(slot, geom, prim, bary, t, origin, dir, c);
        } else {
            fetchSurface(slot, geom, prim, bary, t, origin, dir, true, c);
        }
        if (surfaceIsHole(c) || c.isOutline) {
            continue;   // a hole or an outline triangle: the ray passes and nothing is filtered
        }
        if (!isInterface(c)) {
            rayQueryConfirmIntersectionEXT(sq);
            continue;
        }
        transparencyCandidate();
        rememberInterfaceCandidate(candidates, count, overflowT, InterfaceCandidate(uvec3(slot, geom, prim), bary, t));
    }
    bool hasHit = rayQueryGetIntersectionTypeEXT(sq, true) == gl_RayQueryCommittedIntersectionTriangleEXT;
    float endT = hasHit ? rayQueryGetIntersectionTEXT(sq, true) : reach;
    if (hasHit) {
        InterfaceCandidate terminal = InterfaceCandidate(uvec3(
            uint(rayQueryGetIntersectionInstanceCustomIndexEXT(sq, true)),
            uint(rayQueryGetIntersectionGeometryIndexEXT(sq, true)),
            uint(rayQueryGetIntersectionPrimitiveIndexEXT(sq, true))),
            rayQueryGetIntersectionBarycentricsEXT(sq, true), endT);
        fetchInterfaceCandidate(terminal, origin, dir, hit);
    }
    float lastT = 0.0;
    uint layers = 0u;
    for (uint i = 0u; i < count; i++) {
        float t = candidates[i].t;
        if (t >= endT) {
            break;
        }
        Surface c;
        fetchInterfaceCandidate(candidates[i], origin, dir, c);
        if (m.inside) {
            vec3 T;
            vec3 ins;
            waterSegment(m, origin + dir * lastT, dir, max(t - lastT, 0.0), T, ins);
            inscatter += filterOut * ins;
            filterOut *= T;
        }
        lastT = t;
        transparencyLayer();
        filterOut *= interfaceTransmittance(c, interfaceReflectance(c, dir), dir);
        crossWaterBoundary(m, c, dir);
        if (max(filterOut.r, max(filterOut.g, filterOut.b)) < 0.004) {
            filterOut = vec3(0.0);
            return false;
        }
        if (++layers >= 16u && overflowT < endT) {
            filterOut = vec3(0.0); // an unresolved deeper layer must not leak sky through an untraced blocker
            return false;
        }
    }
    if (m.inside) {
        vec3 T;
        vec3 ins;
        float tail = hasHit ? max(endT - lastT, 0.0) : clamp(reach - lastT, 0.0, 48.0);
        waterSegment(m, origin + dir * lastT, dir, tail, T, ins);
        inscatter += filterOut * ins;
        filterOut *= T;
    }
    return hasHit;
}

bool traceThroughInterfaces(vec3 origin, vec3 dir, float reach, out Surface hit, out vec3 filterOut) {
    WaterMedium m = noWater();
    vec3 ins;
    return traceThroughInterfacesM(origin, dir, reach, m, hit, filterOut, ins);
}

// Sun / moon light that reaches a point under water: the straight line to the light is wrong there. The light bends at the surface
// (Snell), so the shadow ray first goes along the REFRACTED direction Lw up to the water surface (walls, floors and overhangs of
// the pool cast their shadows with the refracted angle), then continues along L to the sky. The light arriving on the surface is
// weighted by (1 - Fresnel), the absorption of the water column on the way and the analytic caustic of the wave field at that
// depth (rt_caustics.glsl). Returns false when the light is blocked. Lw is the direction to use for the cosine terms of the
// shaded point, tr the RGB gain including the beam narrowing cos(L)/cos(Lw) (irradiance on a horizontal floor stays E cos(L)).
// worldXZ: world position (the frame of Surface.worldPos) of the shaded point, the wave field is evaluated there.
bool waterSunBeam(vec3 so, vec2 worldXZ, vec3 L, WaterMedium m, out vec3 Lw, out vec3 shOrigin, out vec3 shDir, out vec3 gain) {
    Lw = L;
    shOrigin = so;
    shDir = L;
    gain = vec3(1.0);
    if (L.y < 0.03) {
        return false;
    }
    Lw = -refract(-L, vec3(0.0, 1.0, 0.0), 1.0 / m.ior);
    vec3 o = so;
    bool found = false;
    float travel = 0.0;
    vec3 hpos = so;
    for (uint i = 0u; i < 4u; i++) {
        float t;
        vec3 tint;
        vec4 optical;
        uint k = traceWaterBoundary(o, Lw, 200.0, false, t, tint, optical);
        if (k == 0u) {
            break;
        }
        travel += t;
        hpos = o + Lw * t;
        if (k == 1u) {
            found = true;
            break;
        }
        if (k == 2u) {
            return false;   // the pool wall / an overhang blocks the refracted beam
        }
        o = hpos + Lw * 0.002;   // glass layer in the water: let the beam through
    }
    if (!found) {
        Lw = L;   // no water above (the medium state was stale): plain straight sun
        return true;
    }
    float depth = max(hpos.y - so.y, 0.0);
    float F = fresnelDielectric(L.y, m.ior);
    float c = waterCaustic(worldXZ, L, depth, waterTime());
    // the rest of the beam (sky side) is the caller's single traceTransmittance(shOrigin, shDir)
    shOrigin = hpos + L * 0.003;
    gain = exp(-m.sigmaT * travel) * ((1.0 - F) * (L.y / max(Lw.y, 0.05)) * c);
    return true;
}

