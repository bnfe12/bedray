// Temporal history contract for Bedray 2.0.
//
// Every temporal feature (accumulation, ReSTIR reservoirs, Radiance Cascades)
// uses the same reprojection and validation vocabulary.  A pass owns its own
// history buffer, but it cannot invent a second notion of "valid history".

public module history_contract;

enum HistoryStatus : uint {
    Valid       = 0u,
    Disoccluded = 1u,
    OutOfBounds = 2u,
    Reset       = 3u,
    Rejected    = 4u,
};

// Stored once per history buffer, not per pixel.
struct HistoryHeader {
    float4x4 prevViewProjection;
    float4   prevCameraOrigin;   // xyz: previous camera origin, w: previous frame
    uint4    size;               // x: width, y: height, z: frame, w: flags
};

struct HistorySample {
    float4 radiance;        // rgb: radiance, a: confidence (0..1)
    float4 normalRough;     // xyz: world normal, w: roughness
    float4 positionDepth;   // xyz: world position, w: view depth
    uint4  meta;            // x: age, y: HistoryStatus, z: materialId, w: flags
};

struct HistoryPolicy {
    float maxAge;           // frames a sample may survive
    float depthTolerance;   // relative depth difference accepted
    float normalTolerance;  // minimum dot(normal, previous normal)
    float luminanceClamp;   // maximum history/current luminance ratio
    uint  flags;
};

struct Reprojection {
    float2 pixel;
    float  depth;
    HistoryStatus status;
    bool   valid;
};

static const uint HISTORY_POLICY_FLAG_STATIC_CAMERA = 1u << 0u;
static const uint HISTORY_POLICY_FLAG_ALPHA_TEST    = 1u << 1u;
static const uint HISTORY_POLICY_FLAG_RESET         = 1u << 2u;

HistoryPolicy defaultHistoryPolicy() {
    HistoryPolicy policy;
    policy.maxAge = 32.0;
    policy.depthTolerance = 0.02;
    policy.normalTolerance = 0.8;
    policy.luminanceClamp = 16.0;
    policy.flags = 0u;
    return policy;
}

// Projects a world position into the previous frame.  Camera-relative space is
// preserved: the caller passes an absolute world position only at this
// boundary, because reprojection is the one operation that needs it.
Reprojection reprojectToHistory(HistoryHeader header, float3 worldPosition) {
    Reprojection result;
    result.pixel = float2(-1.0);
    result.depth = 0.0;
    result.status = HistoryStatus.OutOfBounds;
    result.valid = false;

    float4 clip = mul(header.prevViewProjection, float4(worldPosition, 1.0));
    if (clip.w <= 1.0e-5) {
        result.status = HistoryStatus.Disoccluded;
        return result;
    }

    float2 ndc = clip.xy / clip.w;
    float2 pixel = (ndc * 0.5 + 0.5) * float2(header.size.xy);
    result.pixel = pixel;
    result.depth = clip.w;

    bool inside = pixel.x >= 0.0 && pixel.y >= 0.0 &&
                  pixel.x < float(header.size.x) && pixel.y < float(header.size.y);
    if (!inside) return result;

    result.status = HistoryStatus.Valid;
    result.valid = true;
    return result;
}

// A history sample is accepted only when geometry, material and age agree.
// Luminance clamping is left to the consumer because the clamp depends on the
// signal (direct light, GI, specular) rather than on the history itself.
HistoryStatus historyValidate(HistorySample sample, float3 position, float3 normal,
        float depth, uint materialId, HistoryPolicy policy) {
    if ((policy.flags & HISTORY_POLICY_FLAG_RESET) != 0u) return HistoryStatus.Reset;
    if (sample.meta.y != uint(HistoryStatus.Valid)) return HistoryStatus(sample.meta.y);
    if (float(sample.meta.x) > policy.maxAge) return HistoryStatus.Reset;

    float3 previousNormal = sample.normalRough.xyz;
    if (dot(normalize(previousNormal), normalize(normal)) < policy.normalTolerance) {
        return HistoryStatus.Disoccluded;
    }

    float previousDepth = sample.positionDepth.w;
    float tolerance = policy.depthTolerance * max(abs(previousDepth), 1.0);
    if (abs(previousDepth - depth) > tolerance) return HistoryStatus.Disoccluded;

    if (materialId != 0u && sample.meta.z != 0u && sample.meta.z != materialId) {
        return HistoryStatus.Disoccluded;
    }

    float3 previousPosition = sample.positionDepth.xyz;
    float positionTolerance = tolerance + 0.05;
    if (distance(previousPosition, position) > positionTolerance) {
        return HistoryStatus.Disoccluded;
    }

    return HistoryStatus.Valid;
}

// Converts a validated sample into the blend weight for a running mean.
// A disoccluded or reset sample contributes nothing; a valid sample fades with
// age so a stale reservoir cannot survive forever.
float historyBlendWeight(HistorySample sample, HistoryPolicy policy) {
    if (sample.meta.y != uint(HistoryStatus.Valid)) return 0.0;
    float age = float(sample.meta.x);
    float ageFade = clamp(1.0 - age / max(policy.maxAge, 1.0), 0.0, 1.0);
    return clamp(sample.radiance.a, 0.0, 1.0) * ageFade;
}

HistorySample emptyHistorySample() {
    HistorySample sample;
    sample.radiance = float4(0.0);
    sample.normalRough = float4(0.0, 0.0, 1.0, 1.0);
    sample.positionDepth = float4(0.0, 0.0, 0.0, 1.0e6);
    sample.meta = uint4(0u, uint(HistoryStatus.Reset), 0u, 0u);
    return sample;
}
