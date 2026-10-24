#ifndef BEDRAY_COLOR_GLSL
#define BEDRAY_COLOR_GLSL

// Iris-compatible option annotations; profile/saved overrides are applied before preprocessing.
#define COLOR_CONTRAST 1.05 // [1.0 1.05 1.1]
#define ENABLE_COLOR_CONTRAST
//#define ENABLE_COLOR_EXPOSURE
#define COLOR_EXPOSURE 1.0 // [1.0 1.05 1.1]

vec3 bedrayPostColor(vec3 color) {
#ifdef ENABLE_COLOR_CONTRAST
    return clamp((color - 0.5) * COLOR_CONTRAST + 0.5, 0.0, 1.0);
#else
    return color;
#endif
}

vec3 bedrayExposure(vec3 color) {
#ifdef ENABLE_COLOR_EXPOSURE
    return clamp(color * COLOR_EXPOSURE, 0.0, 1.0);
#else
    return color;
#endif
}

#endif
