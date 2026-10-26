// Sample generation of the ray tracing shaders (owner: denoise). Replaces per-pixel white noise (pcgHash of a linear pixel/frame
// combination) by a spatio-temporal low-discrepancy sequence:
//  - spatial: interleaved gradient noise (Jimenez 2014, "Next Generation Post Processing in Call of Duty: Advanced Warfare") gives
//    every pixel a Cranley-Patterson rotation with a blue-noise-like spectrum, so the error of neighbouring pixels is
//    anti-correlated and the residual noise is high-frequency (the spatial filter and DLSS remove it much better than white noise);
//  - temporal: the 2D R2 sequence (Roberts 2018, "The Unreasonable Effectiveness of Quasirandom Sequences") over frame * count +
//    sample index, so the accumulated mean converges like 1/N instead of 1/sqrt(N).
// Usage: vec2 u = noise2D(p, pc.frame, i, n, dim) for sample i of n in the frame; `dim` decorrelates independent decisions of one
// pixel (0 shadow, 1 ambient occlusion, 2 diffuse bounce, 3 reflection, 4 spare).
// Shadow / light functions (traceVisibility, sun shadow, ambient) stay with their owner: they only need to take their
// 2D sample from noise2D (e.g. coneAroundU) instead of rand01.
float ign(vec2 p) {
    return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

vec2 noise2D(ivec2 p, uint frame, uint i, uint count, uint dim) {
    vec2 q = vec2(p) + vec2(float(dim) * 47.3, float(dim) * 91.7);
    vec2 spatial = vec2(ign(q), ign(q.yx + vec2(5.588238, 17.0)));
    // R2 over the unrolled sample index; the frame index is wrapped to stay exact in float
    float k = float((frame & 1023u) * count + i);
    vec2 seq = fract(k * vec2(0.7548776662466927, 0.5698402909980532));
    return fract(spatial + seq);
}

// Uniform direction inside a cone of half-angle `radius` around d from a 2D sample (same mapping as coneAround).
vec3 coneAroundU(vec3 d, float radius, vec2 u) {
    vec3 t;
    vec3 b;
    basisAround(d, t, b);
    float cosMax = cos(radius);
    float cosT = mix(1.0, cosMax, u.x);
    float sinT = sqrt(max(0.0, 1.0 - cosT * cosT));
    float phi = 6.28318530718 * u.y;
    return normalize(t * (cos(phi) * sinT) + b * (sin(phi) * sinT) + d * cosT);
}

// Cosine-weighted hemisphere direction around n from a 2D sample (same mapping as cosineAround).
vec3 cosineAroundU(vec3 n, vec2 u) {
    vec3 t;
    vec3 b;
    basisAround(n, t, b);
    vec3 h = cosineHemisphere(u);
    return normalize(t * h.x + b * h.y + n * h.z);
}
