// Visibility of a ray (1 = nothing hit): terminate on first hit, cutout alpha-tested, translucent surfaces let light pass.
float traceVisibility(vec3 origin, vec3 d, float tmax) {
    if (tmax <= 0.001) {
        return 1.0;
    }
    rayQueryEXT sq;
    rayQueryInitializeEXT(sq, tlas, gl_RayFlagsTerminateOnFirstHitEXT, 0x03, origin, 0.001, d, tmax);
    while (rayQueryProceedEXT(sq)) {
        if (rayQueryGetIntersectionTypeEXT(sq, false) == gl_RayQueryCandidateIntersectionTriangleEXT
            && CANDIDATE_ACCEPTED(sq, true)) {
            rayQueryConfirmIntersectionEXT(sq);
        }
    }
    return rayQueryGetIntersectionTypeEXT(sq, true) == gl_RayQueryCommittedIntersectionNoneEXT ? 1.0 : 0.0;
}

// Forward declaration of RGB transmittance along a shadow ray
vec3 traceTransmittance(vec3 origin, vec3 d, float tmax);

// Distance to the nearest opaque/cutout hit along d, or tmax when nothing is hit (translucent surfaces let the ray pass).
float traceDist(vec3 origin, vec3 d, float tmax) {
    if (tmax <= 0.001) {
        return 0.0;
    }
    rayQueryEXT sq;
    rayQueryInitializeEXT(sq, tlas, gl_RayFlagsNoneEXT, 0x03, origin, 0.001, d, tmax);
    while (rayQueryProceedEXT(sq)) {
        if (rayQueryGetIntersectionTypeEXT(sq, false) == gl_RayQueryCandidateIntersectionTriangleEXT
            && CANDIDATE_ACCEPTED(sq, true)) {
            rayQueryConfirmIntersectionEXT(sq);
        }
    }
    if (rayQueryGetIntersectionTypeEXT(sq, true) == gl_RayQueryCommittedIntersectionNoneEXT) {
        return tmax;
    }
    return rayQueryGetIntersectionTEXT(sq, true);
}

uint pcgHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float rand01(inout uint seed) {
    seed = pcgHash(seed);
    return float(seed) * (1.0 / 4294967296.0);
}

// Cosine-weighted hemisphere sample around +Z (RtSampling.cosineHemisphere)
vec3 cosineHemisphere(vec2 u) {
    float r = sqrt(u.x);
    float phi = 6.28318530718 * u.y;
    return vec3(r * cos(phi), r * sin(phi), sqrt(max(1.0 - u.x, 0.0)));
}

// Orthonormal basis around n (Duff et al. 2017)
void basisAround(vec3 n, out vec3 t, out vec3 b) {
    float sgn = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (sgn + n.z);
    float c = n.x * n.y * a;
    t = vec3(1.0 + sgn * n.x * n.x * a, sgn * c, -sgn * n.x);
    b = vec3(c, sgn + n.y * n.y * a, -n.y);
}

vec3 cosineAround(vec3 n, inout uint seed) {
    vec3 t;
    vec3 b;
    basisAround(n, t, b);
    vec3 h = cosineHemisphere(vec2(rand01(seed), rand01(seed)));
    return normalize(t * h.x + b * h.y + n * h.z);
}

// Uniform direction inside a cone of half-angle `radius` (radians) around d.
vec3 coneAround(vec3 d, float radius, inout uint seed) {
    vec3 t;
    vec3 b;
    basisAround(d, t, b);
    float cosMax = cos(radius);
    float cosT = mix(1.0, cosMax, rand01(seed));
    float sinT = sqrt(max(0.0, 1.0 - cosT * cosT));
    float phi = 6.28318530718 * rand01(seed);
    return normalize(t * (cos(phi) * sinT) + b * (sin(phi) * sinT) + d * cosT);
}

#include "rt_noise.glsl"

// ---- Low-discrepancy sampling of the sun / moon disk (shadow rays) ---------------------------------------------------------
// Independent white-noise directions need many samples to cover a penumbra, and a temporal accumulation of them leaves a
// grain along every shadow edge. The shadow rays use the R2 sequence (Roberts 2018, additive recurrence of the plastic number)
// instead: sample n of a pixel is fract(offset + n * (1/g, 1/g^2)), so any run of consecutive frames covers the disk evenly and
// the penumbra converges in a handful of samples. `offset` rotates the sequence per pixel (Cranley-Patterson); pixelOffset()
// is the spatial interleaved-gradient-noise rotation of rt_noise.glsl (noise2D at frame 0, sample 0 leaves only the IGN term).
vec2 pixelOffset(ivec2 p) {
    return noise2D(p, 0u, 0u, 1u, 0u);
}

vec2 r2Point(vec2 offset, uint n) {
    return fract(offset + float(n) * vec2(0.75487766624669276, 0.56984029099805327));
}

// Point of the unit disk by the concentric (Shirley-Chiu) map: area preserving, so strata stay strata.
vec2 concentricDisk(vec2 u) {
    vec2 a = 2.0 * u - 1.0;
    if (a.x == 0.0 && a.y == 0.0) {
        return vec2(0.0);
    }
    float r;
    float theta;
    if (abs(a.x) > abs(a.y)) {
        r = a.x;
        theta = 0.78539816339 * (a.y / a.x);
    } else {
        r = a.y;
        theta = 1.57079632679 - 0.78539816339 * (a.x / a.y);
    }
    return r * vec2(cos(theta), sin(theta));
}

// Direction inside the sun / moon disk of angular radius `radius` around d for the sample point u in [0,1)^2.
vec3 sunDiskDirection(vec3 d, float radius, vec2 u) {
    vec3 t;
    vec3 b;
    basisAround(d, t, b);
    vec2 q = concentricDisk(u) * tan(radius);
    return normalize(d + t * q.x + b * q.y);
}

// Shadow direction of sample `i` of this frame for a shaded fragment, with the sun-disk rotation taken from `rotation`:
// the pixel index normally, the world cell index when the pixel-shadow option samples the shadow per cell
// (rt_common_pixel_shadow.glsl sunShadowRotation). The rotation is passed in, not held in a global, so shading a secondary
// ray can never change the sample sequence of the primary pixel that spawned it.
vec3 sunShadowDirectionAt(vec3 d, float radius, ivec2 rotation, uint frame, uint i, uint perFrame) {
    // The frame is wrapped exactly like noise2D: the sequence stays exact in a long session, and the 1024-frame repeat is
    // invisible to a 1/N penumbra (the alternative was a sequence index that drifts into float granularity).
    return sunDiskDirection(d, radius, r2Point(pixelOffset(rotation), (frame & 1023u) * perFrame + i));
}

