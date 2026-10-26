// Bedray 2.0 — temporal accumulation (module).
//
// First stage of the SVGF-style denoiser chain: reproject last frame's filtered radiance, validate it with
// the shared history vocabulary (contracts/history.slang) and blend it into the current noisy sample with a
// running mean. The age of a pixel is carried in the alpha channel of the history image, so the à-trous
// stage can read "how converged is this pixel" without a second buffer.

public module temporal;

import "contracts/history.slang";

struct TemporalPush {
    float blendFloor;      // 0 smallest weight of the current sample (1/age saturates here)
    float clampSigma;      // 4 neighbourhood colour clamp, in standard deviations
    uint  flags;           // 8 HISTORY_POLICY_FLAG_*
    uint  frame;           // 12 current frame index
};

/** Result of one accumulation step. `age` is the number of frames already folded into `radiance`. */
struct TemporalResult {
    float3 radiance;
    float  age;
};

bool temporalFinite(float3 v) {
    return !(any(isnan(v)) || any(isinf(v)));
}

bool temporalFiniteScalar(float x) {
    return !(isnan(x) || isinf(x));
}

/**
 * Unit vector from a stored normal. Rejects non-finite input and anything whose length is not close to one
 * (a G-buffer normal is unit by contract). Scaling by the largest component first keeps the squared length
 * from overflowing for huge finite values.
 */
bool temporalUnitNormal(float3 n, out float3 unit) {
    unit = float3(0.0, 0.0, 1.0);
    if (!temporalFinite(n)) return false;
    float m = max(abs(n.x), max(abs(n.y), abs(n.z)));
    if (!(m > 1.0e-6) || m > 1.0e15) return false;
    float3 scaled = n / m;
    float len = length(scaled) * m;
    if (!(len > 0.5) || len > 2.0) return false;
    unit = scaled / length(scaled);
    return true;
}

/**
 * Validates one history texel. No previous world position or material id is stored, so ONLY these checks
 * exist: age, normal agreement and depth agreement. `normal` must already be a unit vector and
 * `expectedDepth` finite and positive. `expectedDepth` is where the current surface point lands in the
 * previous camera (Reprojection.depth); comparing it with the stored previous depth detects disocclusion.
 * Position and material rejection are NOT implemented until a previous-position / material-identity
 * resource exists. Any non-finite or out-of-domain history is a reset, never a valid sample.
 *
 * Depth convention: both depths must be the same linear depth. Reprojection.depth is clip.w of
 * prevViewProjection, which equals -viewZ only for a plain perspective projection; the producer of
 * HistoryHeader must guarantee that (unresolved, see the resource table).
 */
HistoryStatus temporalValidate(float4 history, float4 historyGeom, float3 normal, float expectedDepth,
        HistoryPolicy policy) {
    if ((policy.flags & HISTORY_POLICY_FLAG_RESET) != 0u) return HistoryStatus.Reset;
    float3 prevNormal;
    if (!temporalFinite(history.rgb) || !temporalFiniteScalar(history.a) ||
            !temporalFiniteScalar(historyGeom.w) || !temporalFiniteScalar(expectedDepth) ||
            !temporalUnitNormal(historyGeom.xyz, prevNormal)) {
        return HistoryStatus.Rejected;
    }
    // A pixel that was accumulated has age >= 1; age 0 is the "sky / no history" marker.
    if (history.a < 1.0) return HistoryStatus.Reset;
    if (history.a > policy.maxAge) return HistoryStatus.Reset;
    if (historyGeom.w <= 0.0 || expectedDepth <= 0.0) return HistoryStatus.Disoccluded;
    if (dot(prevNormal, normal) < policy.normalTolerance) return HistoryStatus.Disoccluded;
    float tolerance = policy.depthTolerance * max(expectedDepth, 1.0);
    if (!(abs(historyGeom.w - expectedDepth) <= tolerance)) return HistoryStatus.Disoccluded;
    return HistoryStatus.Valid;
}

/**
 * Clamps history towards the current 3x3 neighbourhood so a stale, bright sample cannot ghost.
 * `mean` and `deviation` are measured on the current frame's noisy input.
 */
float3 temporalClampHistory(float3 history, float3 mean, float3 deviation, float sigma) {
    float3 lo = mean - deviation * sigma;
    float3 hi = mean + deviation * sigma;
    return clamp(history, lo, hi);
}

/**
 * Folds `current` into the validated `history`. A rejected history restarts the mean from the current
 * sample (age 0 -> 1), which is the only case where the output is pure noise.
 */
TemporalResult temporalAccumulate(float3 current, float3 history, float historyAge,
        HistoryStatus status, HistoryPolicy policy, TemporalPush pc) {
    TemporalResult result;
    if (status != HistoryStatus.Valid) {
        result.radiance = current;
        result.age = 1.0;
        return result;
    }
    float age = min(historyAge + 1.0, policy.maxAge);
    float alpha = max(1.0 / age, pc.blendFloor);
    result.radiance = lerp(history, current, alpha);
    result.age = age;
    return result;
}
