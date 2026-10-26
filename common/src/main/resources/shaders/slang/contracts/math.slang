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
    return value == value && abs(value) < 3.402823466e+38;
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
