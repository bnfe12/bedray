// SPDX-License-Identifier: MIT
#ifndef VIGNETTE_GLSL
#define VIGNETTE_GLSL

float computeVignette(vec2 uv, float strength) {
    vec2 coord = uv - vec2(0.5);
    return clamp(1.0 - strength * dot(coord, coord), 0.0, 1.0);
}

#endif
