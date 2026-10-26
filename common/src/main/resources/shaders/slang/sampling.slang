// sampling.slang — Random number generation, hemisphere sampling, sun disk sampling.
// Ported 1:1 from rt_common_sampling.glsl and rt_noise.glsl.
// Pure math — no scene access, no side effects.

module sampling;

import types;

// ---- PCG hash (random number generator) ----

uint pcgHash(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word  = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

float rand01(inout uint seed) {
    seed = pcgHash(seed);
    return float(seed) * (1.0 / 4294967296.0);
}

// ---- Hemisphere sampling ----

// Cosine-weighted hemisphere sample around +Z.
float3 cosineHemisphere(float2 u) {
    float r   = sqrt(u.x);
    float phi = TWO_PI * u.y;
    return float3(r * cos(phi), r * sin(phi), sqrt(max(1.0 - u.x, 0.0)));
}

// Orthonormal basis around n (Duff et al. 2017, "Building an Orthonormal Basis").
void basisAround(float3 n, out float3 t, out float3 b) {
    float sgn = n.z >= 0.0 ? 1.0 : -1.0;
    float a   = -1.0 / (sgn + n.z);
    float c   = n.x * n.y * a;
    t = float3(1.0 + sgn * n.x * n.x * a, sgn * c, -sgn * n.x);
    b = float3(c, sgn + n.y * n.y * a, -n.y);
}

// Cosine-weighted direction around normal n.
float3 cosineAround(float3 n, inout uint seed) {
    float3 t, b;
    basisAround(n, t, b);
    float3 h = cosineHemisphere(float2(rand01(seed), rand01(seed)));
    return normalize(t * h.x + b * h.y + n * h.z);
}

// Uniform direction inside a cone of half-angle `radius` (radians) around d.
float3 coneAround(float3 d, float radius, inout uint seed) {
    float3 t, b;
    basisAround(d, t, b);
    float cosMax = cos(radius);
    float cosT   = lerp(1.0, cosMax, rand01(seed));
    float sinT   = sqrt(max(0.0, 1.0 - cosT * cosT));
    float phi    = TWO_PI * rand01(seed);
    return normalize(t * (cos(phi) * sinT) + b * (sin(phi) * sinT) + d * cosT);
}

// ---- Blue noise / interleaved gradient noise ----

// Interleaved gradient noise (Jimenez 2014).
float interleavedGradientNoise(int2 p) {
    float3 magic = float3(0.06711056, 0.00583715, 52.9829189);
    return frac(magic.z * frac(dot(float2(p), magic.xy)));
}

// 2D noise combining IGN spatial rotation and R2 temporal sequence.
float2 noise2D(int2 p, uint frame, uint sampleIndex, uint samplesPerFrame, uint baseOffset) {
    float ign = interleavedGradientNoise(p);
    uint  n   = (frame & 1023u) * samplesPerFrame + sampleIndex + baseOffset;
    float2 r2 = frac(float2(ign) + float(n) * float2(0.75487766624669276, 0.56984029099805327));
    return r2;
}

// ---- R2 low-discrepancy sequence for sun disk sampling ----

float2 pixelOffset(int2 p) {
    return noise2D(p, 0u, 0u, 1u, 0u);
}

float2 r2Point(float2 offset, uint n) {
    return frac(offset + float(n) * float2(0.75487766624669276, 0.56984029099805327));
}

// Concentric disk mapping (Shirley-Chiu): area preserving, strata stay strata.
float2 concentricDisk(float2 u) {
    float2 a = 2.0 * u - 1.0;
    if (a.x == 0.0 && a.y == 0.0) return float2(0.0);
    float r, theta;
    if (abs(a.x) > abs(a.y)) {
        r     = a.x;
        theta = 0.78539816339 * (a.y / a.x);
    } else {
        r     = a.y;
        theta = HALF_PI - 0.78539816339 * (a.x / a.y);
    }
    return r * float2(cos(theta), sin(theta));
}

// Direction inside the sun/moon disk of angular radius `radius` around d for sample point u.
float3 sunDiskDirection(float3 d, float radius, float2 u) {
    float3 t, b;
    basisAround(d, t, b);
    float2 q = concentricDisk(u) * tan(radius);
    return normalize(d + t * q.x + b * q.y);
}

// Shadow direction for sample i of a frame, with per-pixel R2 rotation.
float3 sunShadowDirectionAt(float3 d, float radius, int2 rotation, uint frame, uint i, uint perFrame) {
    return sunDiskDirection(d, radius, r2Point(pixelOffset(rotation), (frame & 1023u) * perFrame + i));
}

// Foliage freezes the frame index to avoid per-frame flicker through thin leaf cards.
uint sunSampleFrame(bool isFoliage, uint frame) {
    return isFoliage ? 0u : frame;
}
