// Bedray 2.0 — screen-space reflections (module).
//
// Backs LightingAlgorithm.ScreenSpace, the cheap reflection path of RenderProfile.Fast in voxel-vanilla mode.
// A reflected ray is marched in view space against the linear depth buffer with a fixed step budget, then
// refined by bisection. A miss reports confidence 0 so the caller falls back to the sky/probe term instead of
// guessing: SSR only ever returns what is actually on screen.

public module ssr;

// Camera contract: right-handed view space, camera looks down -Z, uv origin matching the G-buffer. Linear depth
// is DEFINED here as -viewZ and the G-buffer must write exactly that; nothing below assumes clip.w equals it,
// so any projection that maps view space to NDC xy by clip.xy / clip.w works (jitter, reverse-Z, flipped Y live
// in the matrices). View-space normals. Matrices live in a uniform buffer: two 4x4 matrices would overflow the
// 128-byte push-constant limit.
struct SsrCamera {
    float4x4 proj;          //   0 view -> clip
    float4x4 invProj;       //  64 clip -> view
};

// Compact push block, 16 bytes.
struct SsrPush {
    float  maxDistance;     //  0 view-space march length, > 0
    float  thickness;       //  4 depth slack that still counts as a hit, > 0
    float  roughnessCutoff; //  8 above this the reflection is skipped, (0, 1]
    int    steps;           // 12 linear march steps, 1..256
};

struct SsrHit {
    float2 uv;
    float  confidence;      // 0 = miss
};

bool ssrFinite(float x) {
    return !(isnan(x) || isinf(x));
}

bool ssrFinite3(float3 v) {
    return ssrFinite(v.x) && ssrFinite(v.y) && ssrFinite(v.z);
}

bool ssrParamsValid(SsrPush pc) {
    return ssrFinite(pc.maxDistance) && ssrFinite(pc.thickness) && ssrFinite(pc.roughnessCutoff) &&
           pc.maxDistance > 0.0 && pc.thickness > 0.0 &&
           pc.roughnessCutoff > 0.0 && pc.roughnessCutoff <= 1.0 &&
           pc.steps >= 1 && pc.steps <= 256;
}

/** Unit normal from a stored vector; false for non-finite or non-unit input. Scales first so the length cannot overflow. */
bool ssrUnitNormal(float3 n, out float3 unit) {
    unit = float3(0.0, 0.0, 1.0);
    if (!ssrFinite3(n)) return false;
    float m = max(abs(n.x), max(abs(n.y), abs(n.z)));
    if (!(m > 1.0e-6) || m > 1.0e15) return false;
    float3 scaled = n / m;
    float len = length(scaled) * m;
    if (!(len > 0.5) || len > 2.0) return false;
    unit = scaled / length(scaled);
    return true;
}

/** View-space position of a pixel with the given linear depth. False for degenerate matrices or depths. */
bool ssrViewPosition(float2 uv, float linearDepth, SsrCamera cam, out float3 viewPos) {
    viewPos = float3(0.0);
    if (!ssrFinite(linearDepth) || linearDepth <= 0.0) return false;
    float4 v = mul(cam.invProj, float4(uv.x * 2.0 - 1.0, uv.y * 2.0 - 1.0, 1.0, 1.0));
    if (!ssrFinite(v.w) || abs(v.w) < 1.0e-9) return false;
    float3 dir = v.xyz / v.w;                       // a point on the pixel's view ray
    if (!ssrFinite3(dir) || !(-dir.z > 1.0e-9)) return false;
    viewPos = dir * (linearDepth / -dir.z);
    return ssrFinite3(viewPos);
}

/** Projects a view-space point. `rayDepth` is -viewZ, the same linear depth the G-buffer stores. */
bool ssrProject(float3 viewPos, SsrCamera cam, out float2 uv, out float rayDepth) {
    uv = float2(0.0);
    rayDepth = -viewPos.z;
    if (!ssrFinite3(viewPos) || !(rayDepth > 1.0e-3)) return false;
    float4 clip = mul(cam.proj, float4(viewPos, 1.0));
    if (!ssrFinite(clip.w) || !(clip.w > 1.0e-5)) return false;
    uv = clip.xy / clip.w * 0.5 + 0.5;
    return ssrFinite(uv.x) && ssrFinite(uv.y);
}

/** Edge fade so a hit close to the screen border does not pop when the camera turns. */
float ssrEdgeFade(float2 uv) {
    float2 d = min(uv, 1.0 - uv);
    return saturate(min(d.x, d.y) * 10.0);
}

interface IDepthSource {
    /** Linear view depth at `uv`; <= 0 means sky. May return non-finite values, which the trace treats as a miss. */
    float linearDepth(float2 uv);
}

/**
 * Marches `viewDir` (unit, view space) from `origin` (view space, on the reflecting surface).
 *
 * Bracket rule: `lo` is always a ray parameter whose sample was verified to be in front of a real surface
 * (t = 0 is the reflecting surface itself, in front by construction); `hi` is the first sample behind a
 * surface within `thickness`. Sky, off-screen or non-finite samples never move `lo` and abort refinement
 * as a MISS, because no ordering against geometry can be proven there.
 */
SsrHit ssrTrace<D : IDepthSource>(D depthAt, SsrCamera cam, float3 origin, float3 viewDir, float roughness, SsrPush pc) {
    SsrHit hit;
    hit.uv = float2(0.0);
    hit.confidence = 0.0;
    if (!ssrParamsValid(pc) || !ssrFinite(roughness) || roughness < 0.0 || roughness > pc.roughnessCutoff ||
            !ssrFinite3(origin) || !ssrFinite3(viewDir) || origin.z > -1.0e-3) {
        return hit;
    }

    float stepLen = pc.maxDistance / float(pc.steps);
    float t = stepLen;
    float lo = 0.0;
    for (int i = 0; i < pc.steps; ++i) {
        float2 uv;
        float rayDepth;
        if (!ssrProject(origin + viewDir * t, cam, uv, rayDepth)) break;   // behind the camera or degenerate
        if (any(uv < 0.0) || any(uv > 1.0)) break;                          // left the screen
        float sceneDepth = depthAt.linearDepth(uv);
        if (!ssrFinite(sceneDepth)) break;
        if (sceneDepth > 0.0) {
            float delta = rayDepth - sceneDepth;
            if (delta > 0.0 && delta < pc.thickness) {
                float hi = t;
                for (int k = 0; k < 5; ++k) {
                    float mid = 0.5 * (lo + hi);
                    float2 muv;
                    float md;
                    if (!ssrProject(origin + viewDir * mid, cam, muv, md)) return hit;
                    float sd = depthAt.linearDepth(muv);
                    if (!ssrFinite(sd) || sd <= 0.0) return hit;           // cannot prove ordering: miss
                    if (md - sd > 0.0) {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                float2 huv;
                float hd;
                if (!ssrProject(origin + viewDir * hi, cam, huv, hd)) return hit;
                hit.uv = huv;
                float travel = saturate(hi / pc.maxDistance);
                hit.confidence = ssrEdgeFade(huv) * (1.0 - travel) * (1.0 - roughness / pc.roughnessCutoff);
                return hit;
            }
            if (delta <= 0.0) {
                lo = t;                  // verified in front of a real surface
            }
        }
        t += stepLen;
    }
    return hit;
}
