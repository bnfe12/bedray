// Pure math shared by Bedray 2.0 modules.
//
// This module has no descriptors, no scene data and no legacy ABI.  It exists
// so new shading/light modules do not have to import types.slang just to get a
// constant or a luminance helper.

public module math_contract;

static const float PI      = 3.14159265358979323846;
static const float TWO_PI  = 6.28318530718;
static const float INV_PI  = 0.31830988618;
static const float HALF_PI = 1.57079632679;

static const float MATH_EPSILON = 1.0e-6;

float luminance(float3 color) {
    return dot(color, float3(0.2126, 0.7152, 0.0722));
}

float pow5(float x) {
    float x2 = x * x;
    return x2 * x2 * x;
}

float3 safeNormalize(float3 value, float3 fallback) {
    float lengthSquared = dot(value, value);
    return lengthSquared > MATH_EPSILON ? value * rsqrt(lengthSquared) : fallback;
}

float2 safeNormalize(float2 value, float2 fallback) {
    float lengthSquared = dot(value, value);
    return lengthSquared > MATH_EPSILON ? value * rsqrt(lengthSquared) : fallback;
}

// Keeps a shading normal on the same side as the geometric normal.  A normal
// map must not turn a surface inside out when the ray hits its back face.
float3 orientNormal(float3 normal, float3 geometricNormal) {
    return dot(normal, geometricNormal) < 0.0 ? -normal : normal;
}

bool isFinite(float value) {
    // Inf and NaN both carry an all-ones exponent.  A bit test survives fast-math:
    // the compiler is allowed to fold `value == value` to true and to assume that
    // ordered comparisons with NaN have a definite result.
    return (asuint(value) & 0x7f800000u) != 0x7f800000u;
}

bool isFinite(float3 value) {
    return isFinite(value.x) && isFinite(value.y) && isFinite(value.z);
}

float3 clampRadiance(float3 radiance, float maximum) {
    return clamp(radiance, float3(0.0), float3(maximum));
}

// Stable orthonormal frame for an axis-aligned face normal.  A normal map
// needs a tangent frame even when the provider has no UV derivatives.
void faceTangentFrame(float3 normal, out float3 tangent, out float3 bitangent) {
    float3 up = abs(normal.y) < 0.99 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0);
    tangent = safeNormalize(cross(up, normal), float3(1.0, 0.0, 0.0));
    bitangent = cross(normal, tangent);
}

// Tangent frame implied by a triangle's own UVs, so a normal or a cutout read
// along the texture the author mapped rather than along an axis.  A degenerate
// island - both edges mapping to the same direction, which baked atlases and
// recoloured parts do produce - has no invertible UV gradient, and the face
// frame is the honest answer.
void triangleTangentFrame(float3 p0, float3 p1, float3 p2, float2 uv0, float2 uv1, float2 uv2,
        float3 normal, out float3 tangent, out float3 bitangent) {
    float3 e1 = p1 - p0;
    float3 e2 = p2 - p0;
    float2 d1 = uv1 - uv0;
    float2 d2 = uv2 - uv0;
    float det = d1.x * d2.y - d2.x * d1.y;
    if (abs(det) > 1.0e-12) {
        tangent = safeNormalize((e1 * d2.y - e2 * d1.y) / det, normal);
        bitangent = safeNormalize((e2 * d1.x - e1 * d2.x) / det, normal);
    } else {
        faceTangentFrame(normal, tangent, bitangent);
    }
}
