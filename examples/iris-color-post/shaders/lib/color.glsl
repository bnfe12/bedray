#ifndef BEDRAY_COLOR_GLSL
#define BEDRAY_COLOR_GLSL

// Source defaults; external Iris option/profile files are not evaluated by this milestone.
#define COLOR_CONTRAST 1.05
#define ENABLE_COLOR_CONTRAST

vec3 bedrayPostColor(vec3 color) {
#ifdef ENABLE_COLOR_CONTRAST
    return clamp((color - 0.5) * COLOR_CONTRAST + 0.5, 0.0, 1.0);
#else
    return color;
#endif
}

#endif
