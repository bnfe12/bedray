// Versioned cubemap cells and one shared, haloed spatial lattice. No v1 atlas alias.
public module rc_field_mapping;
import "contracts/math.slang";

static const uint RC_FIELD_VERSION = 2u;
static const uint RC_FIELD_LEVELS = 5u;
static const uint RC_FIELD_TERMINAL_KNOWN = 1u << 16u;

struct RCFieldConstants {
    uint4 header;       // version, levels, base core axis, total tail records
    uint4 counts[5];    // allocated axis, angular side, probe count, tail offset
    float4 origins[5];  // first probe xyz, spacing; one shared center, fixed relative to the raw camera
    float4 ranges[5];   // near radius, far radius, reserved zero
    uint4 tag;          // frame index, reserved zero
    float4 history;     // xyz: this raw camera minus the history field's, w: history weight (0 = none)
};                      // 288 bytes

bool rcFieldMetadataValid(RCFieldConstants f) {
    if (f.header.x != RC_FIELD_VERSION || f.header.y != RC_FIELD_LEVELS
        || f.header.z < 8u || f.header.z > 32u || (f.header.z & (f.header.z - 1u)) != 0u)
        return false;
    uint offset = 0u;
    for (uint c = 0u; c < RC_FIELD_LEVELS; ++c) {
        uint axis = max(1u, f.header.z >> c) + 2u;
        uint side = 1u << c;
        float spacing = float(side);
        float first = -0.5 * float(axis - 1u) * spacing;
        if (any(f.counts[c] != uint4(axis, side, axis * axis * axis, offset))
            || any(f.origins[c] != float4(first, first, first, spacing))
            || any(f.ranges[c] != float4(4.0 * spacing, 8.0 * spacing, 0.0, 0.0))) return false;
        offset += axis * axis * axis * 6u * side * side;
    }
    return f.header.w == offset && all(f.tag.yzw == uint3(0u))
        && isFinite(f.history.xyz) && isFinite(f.history.w) && f.history.w >= 0.0 && f.history.w < 1.0;
}

float3 rcCubeDirection(uint face, float2 uv) {
    float3 p;
    switch (face) {
        case 0u: p = float3(1.0, uv.y, -uv.x); break;
        case 1u: p = float3(-1.0, uv.y, uv.x); break;
        case 2u: p = float3(uv.x, 1.0, -uv.y); break;
        case 3u: p = float3(uv.x, -1.0, uv.y); break;
        case 4u: p = float3(uv.x, uv.y, 1.0); break;
        default: p = float3(-uv.x, uv.y, -1.0); break;
    }
    return normalize(p);
}

void rcFieldCell(uint ray, uint side, out uint face, out uint2 cell) {
    face = ray / (side * side);
    uint local = ray % (side * side);
    cell = uint2(local % side, local / side);
}

float3 rcFieldDirection(uint ray, uint side) {
    uint face; uint2 cell;
    rcFieldCell(ray, side, face, cell);
    return rcCubeDirection(face, (float2(cell) + 0.5) * (2.0 / float(side)) - 1.0);
}

void rcFieldCorners(uint ray, uint side, out float3 corners[4]) {
    uint face; uint2 cell;
    rcFieldCell(ray, side, face, cell);
    float2 lo = float2(cell) * (2.0 / float(side)) - 1.0;
    float2 hi = float2(cell + 1u) * (2.0 / float(side)) - 1.0;
    corners[0] = rcCubeDirection(face, lo);
    corners[1] = rcCubeDirection(face, float2(hi.x, lo.y));
    corners[2] = rcCubeDirection(face, hi);
    corners[3] = rcCubeDirection(face, float2(lo.x, hi.y));
}

float rcSphericalTriangle(float3 a, float3 b, float3 c) {
    return 2.0 * atan2(abs(dot(a, cross(b, c))), 1.0 + dot(a,b) + dot(b,c) + dot(c,a));
}
float rcFieldCellArea(uint ray, uint side) {
    float3 p[4]; rcFieldCorners(ray, side, p);
    return rcSphericalTriangle(p[0], p[1], p[2]) + rcSphericalTriangle(p[0], p[2], p[3]);
}

float3 rcFieldProbe(RCFieldConstants f, uint level, uint3 xyz) {
    return f.origins[level].xyz + float3(xyz) * f.origins[level].w;
}
uint3 rcFieldProbeCoordinates(uint index, uint axis) {
    return uint3(index % axis, (index / axis) % axis, index / (axis * axis));
}
uint rcFieldIndex(RCFieldConstants f, uint level, uint3 xyz, uint ray) {
    uint axis = f.counts[level].x, side = f.counts[level].y;
    return f.counts[level].w + (xyz.x + axis * (xyz.y + axis * xyz.z)) * 6u * side * side + ray;
}

bool rcFieldNeighbors(RCFieldConstants f, uint level, float3 position, out int3 base, out float3 fraction) {
    float3 u = (position - f.origins[level].xyz) / f.origins[level].w;
    base = int3(floor(u)); fraction = u - float3(base);
    if (!isFinite(u)) return false;
    int axis = int(f.counts[level].x);
    // At the last probe, positive weights still refer only to that exact probe.
    return all(base >= int3(0)) && all(base < int3(axis))
        && all(base + int3(fraction > float3(0.0)) < int3(axis));
}
float rcFieldNeighborWeight(float3 fraction, uint corner) {
    float3 w = float3((corner & 1u) != 0u ? fraction.x : 1.0 - fraction.x,
        (corner & 2u) != 0u ? fraction.y : 1.0 - fraction.y,
        (corner & 4u) != 0u ? fraction.z : 1.0 - fraction.z);
    return w.x * w.y * w.z;
}
uint3 rcFieldNeighbor(int3 base, uint corner) {
    return uint3(base + int3(corner & 1u, (corner >> 1u) & 1u, (corner >> 2u) & 1u));
}

// Integral of max(dot(normal,w),0) over a spherical polygon. Clipping uses
// great-circle intersections; vector area integrates cosine without a center-ray
// approximation. The shifted endpoint sphere must contain the gather point.
float rcFieldProjectedArea(float3 corners[4], float3 normal) {
    float3 clipped[8]; uint count = 0u;
    float3 a = corners[3]; float da = dot(normal, a);
    for (uint i = 0u; i < 4u; ++i) {
        float3 b = corners[i]; float db = dot(normal, b);
        if ((da > 0.0) != (db > 0.0))
            clipped[count++] = normalize(lerp(a, b, da / (da - db)));
        if (db > 0.0) clipped[count++] = b;
        a = b; da = db;
    }
    if (count < 3u) return 0.0;
    float3 area = float3(0.0);
    a = clipped[count - 1u];
    for (uint j = 0u; j < count; ++j) {
        float3 b = clipped[j]; float3 axis = cross(a,b);
        float sine = length(axis);
        if (sine > 1.0e-7) area += axis * (atan2(sine, dot(a,b)) / sine);
        a = b;
    }
    return max(0.5 * dot(normal, area), 0.0);
}
