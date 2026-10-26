// Bedray 2.0 — edge-avoiding à-trous wavelet filter (module).
//
// Backs DenoiserMode.ATrous of the render plan. One dispatch is one wavelet level: the host runs it
// `levels` times, doubling `stepSize` each time and ping-ponging between two images. Weights follow
// Dammertz et al. 2010 with the SVGF refinements that matter for a voxel world: geometry is compared by
// normal and linear depth (not position, which is unstable far from the camera), and the colour term is
// scaled by the local luminance deviation so noisy regions are blurred harder than converged ones.
//
// The module owns only the weighting maths. Bindings live in passes/atrous.slang, so the same maths can
// be reused by an SVGF variant without a second copy of the kernel.

public module atrous;

/** B3 spline kernel, the standard à-trous footprint: 1/16 [1 4 6 4 1]. */
static const float ATROUS_KERNEL[3] = { 3.0 / 8.0, 1.0 / 4.0, 1.0 / 16.0 };

static const float3 ATROUS_LUMA = float3(0.2126, 0.7152, 0.0722);

// Push constants. Offsets are checked by the Java caller through the generated manifest.
struct ATrousPush {
    int   stepSize;        //  0 tap spacing in pixels: 1, 2, 4, ...
    float sigmaColor;      //  4 colour edge sensitivity (phi_c); lower keeps more detail
    float sigmaNormal;     //  8 normal exponent (phi_n); higher rejects more
    float sigmaDepth;      // 12 relative linear-depth tolerance (phi_z)
};

/** Geometry of one pixel as the filter sees it. */
struct ATrousTap {
    float3 radiance;
    float3 normal;
    float  depth;          // linear view depth; <= 0 or >= skyDepth means "no surface"
    bool   valid;
};

/** Host contract: stepSize >= 1 and finite positive sigmas. A push block that violates it makes the pass copy its input. */
bool atrousPushValid(ATrousPush pc) {
    return pc.stepSize >= 1 && pc.stepSize <= 1024 &&
           pc.sigmaColor > 0.0 && pc.sigmaNormal >= 0.0 && pc.sigmaDepth > 0.0 &&
           !isinf(pc.sigmaColor) && !isinf(pc.sigmaNormal) && !isinf(pc.sigmaDepth);
}

/** Unit normal from a stored vector; false for non-finite or non-unit input. Scales first so the length cannot overflow. */
bool atrousUnitNormal(float3 n, out float3 unit) {
    unit = float3(0.0, 0.0, 1.0);
    if (any(isnan(n)) || any(isinf(n))) return false;
    float m = max(abs(n.x), max(abs(n.y), abs(n.z)));
    if (!(m > 1.0e-6) || m > 1.0e15) return false;
    float3 scaled = n / m;
    float len = length(scaled) * m;
    if (!(len > 0.5) || len > 2.0) return false;
    unit = scaled / length(scaled);
    return true;
}

/** Age travels in alpha; a non-finite or negative age becomes 0, which the temporal stage treats as a reset. */
float atrousSanitizeAge(float a) {
    return (isnan(a) || isinf(a) || a < 0.0) ? 0.0 : a;
}

float atrousLuma(float3 c) {
    return dot(c, ATROUS_LUMA);
}

float3 atrousSanitize(float3 c) {
    if (any(isnan(c)) || any(isinf(c))) {
        return float3(0.0);
    }
    return max(c, float3(0.0));
}

/**
 * Edge-stopping weight of `tap` relative to the centre pixel.
 *
 * `lumaScale` is the centre pixel's local luminance deviation (see atrousLocalDeviation); dividing by it
 * makes the colour term adaptive instead of a global constant.
 */
float atrousWeight(ATrousTap center, ATrousTap tap, float kernelWeight, float lumaScale, ATrousPush pc) {
    if (!tap.valid) {
        return 0.0;
    }
    float wNormal = pow(max(dot(center.normal, tap.normal), 0.0), pc.sigmaNormal);
    float depthDelta = abs(center.depth - tap.depth) / max(center.depth * pc.sigmaDepth, 1.0e-4);
    float wDepth = exp(-depthDelta);
    float colorDelta = abs(atrousLuma(center.radiance) - atrousLuma(tap.radiance));
    float wColor = exp(-colorDelta / max(pc.sigmaColor * lumaScale, 1.0e-4));
    return kernelWeight * wNormal * wDepth * wColor;
}

/**
 * Local luminance deviation from the 3x3 neighbourhood of the centre pixel, in the same units as the
 * luminance difference it normalises. Never returns zero, so a flat patch still has a defined colour term.
 */
float atrousLocalDeviation(float centerLuma, float meanLuma, float meanLumaSq) {
    float variance = max(meanLumaSq - meanLuma * meanLuma, 0.0);
    return max(sqrt(variance), 0.05 * max(centerLuma, 0.0) + 1.0e-3);
}
