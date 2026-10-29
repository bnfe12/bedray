// Radiance Cascades scheduling and probe addressing.
//
// This is deliberately independent of triangle and voxel traversal.  A pass
// supplies radiance samples through a backend, while this module owns the
// multi-scale spatial/angular layout shared by every renderer tier.

public module radiance_cascades;

static const uint RADIANCE_CASCADE_COUNT = 5u;
static const uint RADIANCE_BASE_PROBE_AXIS = 32u;
static const uint RADIANCE_BASE_RAY_COUNT = 16u;
static const float RADIANCE_BASE_SPACING = 1.0;

struct RadianceCascadeDesc {
    uint index;
    uint probeAxis;
    uint rayCount;
    float spacing;
    float nearDistance;
    float farDistance;
};

struct RadianceProbeAddress {
    uint cascade;
    uint probeIndex;
    uint rayIndex;
};

struct RadianceRay {
    float3 origin;
    float3 direction;
    float nearDistance;
    float farDistance;
    float solidAngle;
};

uint cascadeProbeAxis(uint cascade) {
    // Doubling distance while halving spatial resolution keeps a stable probe
    // budget and allocates angular resolution where it produces visible GI.
    return max(1u, RADIANCE_BASE_PROBE_AXIS >> cascade);
}

uint cascadeRayCount(uint cascade) {
    return RADIANCE_BASE_RAY_COUNT << (cascade * 2u);
}

RadianceCascadeDesc radianceCascade(uint cascade) {
    RadianceCascadeDesc result;
    result.index = cascade;
    result.probeAxis = cascadeProbeAxis(cascade);
    result.rayCount = cascadeRayCount(cascade);
    result.spacing = RADIANCE_BASE_SPACING * float(1u << cascade);
    result.nearDistance = result.spacing * 0.5;
    result.farDistance = result.spacing * 4.0;
    return result;
}

uint probeCount(RadianceCascadeDesc cascade) {
    return cascade.probeAxis * cascade.probeAxis * cascade.probeAxis;
}

uint probeLinearIndex(uint3 coordinate, uint axis) {
    return coordinate.x + axis * (coordinate.y + axis * coordinate.z);
}

uint3 probeCoordinate(uint index, uint axis) {
    uint xy = axis * axis;
    return uint3(index % axis, (index / axis) % axis, index / xy);
}

float radicalInverseVdC(uint bits) {
    bits = (bits << 16u) | (bits >> 16u);
    bits = ((bits & 0x55555555u) << 1u) | ((bits & 0xAAAAAAAAu) >> 1u);
    bits = ((bits & 0x33333333u) << 2u) | ((bits & 0xCCCCCCCCu) >> 2u);
    bits = ((bits & 0x0F0F0F0Fu) << 4u) | ((bits & 0xF0F0F0Fu) >> 4u);
    bits = ((bits & 0x00FF00FFu) << 8u) | ((bits & 0xFF00FF00u) >> 8u);
    return float(bits) * 2.3283064365386963e-10;
}

// Equal-area spherical Fibonacci directions avoid polar clustering and remain
// deterministic across temporal frames, a necessary property for reservoir
// reuse and denoisers.
float3 cascadeDirection(uint rayIndex, uint rayCount, uint rotation) {
    // Use the same wrapped index for both coordinates. Rotation permutes the
    // direction set; the probe/ray index remains the persistent store address.
    uint rotatedIndex = (rayIndex + rotation % rayCount) % rayCount;
    float index = float(rotatedIndex) + 0.5;
    float y = 1.0 - 2.0 * index / float(rayCount);
    float radius = sqrt(max(0.0, 1.0 - y * y));
    float phi = 6.283185307179586 * radicalInverseVdC(rotatedIndex);
    return float3(cos(phi) * radius, y, sin(phi) * radius);
}

float3 cascadeProbePosition(RadianceCascadeDesc cascade, uint probeIndex, float3 cameraOrigin) {
    uint axis = cascade.probeAxis;
    uint3 coordinate = probeCoordinate(probeIndex, axis);
    float3 local = (float3(coordinate) + 0.5 - float(axis) * 0.5) * cascade.spacing;
    // Cascades are camera-relative by design.  The resource allocator scrolls
    // the clipmap instead of invalidating all probes when the player moves.
    return floor(cameraOrigin / cascade.spacing) * cascade.spacing + local;
}

RadianceRay cascadeRay(RadianceCascadeDesc cascade, uint probeIndex, uint rayIndex,
        uint frameRotation, float3 cameraOrigin) {
    RadianceRay result;
    result.origin = cascadeProbePosition(cascade, probeIndex, cameraOrigin);
    result.direction = cascadeDirection(rayIndex, cascade.rayCount, frameRotation);
    result.nearDistance = cascade.nearDistance;
    result.farDistance = cascade.farDistance;
    result.solidAngle = 12.566370614359172 / float(cascade.rayCount);
    return result;
}

RadianceProbeAddress decodeRadianceDispatch(uint globalIndex) {
    RadianceProbeAddress result;
    uint remaining = globalIndex;
    for (uint cascade = 0u; cascade < RADIANCE_CASCADE_COUNT; ++cascade) {
        RadianceCascadeDesc desc = radianceCascade(cascade);
        uint workItems = probeCount(desc) * desc.rayCount;
        if (remaining < workItems) {
            result.cascade = cascade;
            result.probeIndex = remaining / desc.rayCount;
            result.rayIndex = remaining % desc.rayCount;
            return result;
        }
        remaining -= workItems;
    }
    // Dispatch dimensions are validated by the host.  This sentinel prevents
    // accidental out-of-bounds writes if a stale command buffer still runs.
    result.cascade = RADIANCE_CASCADE_COUNT;
    result.probeIndex = 0u;
    result.rayIndex = 0u;
    return result;
}
