public module post_cinema;

import sampling;

// Darken towards the corners. uv is the pixel centre in [0,1]; the distance is normalised so a corner sits near 1.
float3 postVignette(float3 color, float2 uv, float strength) {
    float r = length(uv - 0.5) * 1.41421356;
    float vig = 1.0 - strength * smoothstep(0.45, 1.0, r);
    return color * vig;
}

// Animated luminance grain, strongest in the midtones like film, so it neither lifts pure blacks nor clips pure whites.
// The hash mixes the pixel and the frame index, so the grain crawls every frame instead of freezing into a fixed pattern.
float3 postFilmGrain(float3 color, int2 pixel, uint frameIndex, float strength) {
    uint seed = uint(pixel.x) * 1973u + uint(pixel.y) * 9277u + frameIndex * 26699u;
    float n = float(pcgHash(seed)) * (1.0 / 4294967296.0) - 0.5;
    float luma = dot(color, float3(0.2126, 0.7152, 0.0722));
    float weight = 1.0 - abs(2.0 * luma - 1.0);
    return color + n * strength * weight;
}