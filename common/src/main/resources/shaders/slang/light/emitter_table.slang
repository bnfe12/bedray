public module emitter_table;

// BEM2: two uint4 headers, seven uint4 per emitter, ID index, then 48-byte spatial proposal nodes.
static const uint EMITTER_TABLE_MAGIC = 0x42454D32u;
static const uint EMITTER_TABLE_MAX_COUNT = 65536u;
static const uint EMITTER_TABLE_RECORD_WORDS = 7u;

struct BlockEmitterRecord {
    float4 corner;
    float4 edge1;
    float4 edge2;
    float4 uvCorner;
    float4 uvEdges;
    float4 tint;
};

uint blockEmitterCount(StructuredBuffer<uint4> table) {
    uint words, stride;
    table.GetDimensions(words, stride);
    if (words < 2u) return 0u;
    uint4 header = table[0];
    uint4 proposal = table[1];
    float globalFraction = asfloat(proposal.z);
    if (header.x != EMITTER_TABLE_MAGIC || header.y == 0u || header.y > EMITTER_TABLE_MAX_COUNT
            || header.w != 112u || header.z < header.y * 2u || header.z > EMITTER_TABLE_MAX_COUNT * 2u
            || (header.z & (header.z - 1u)) != 0u
            || proposal.x != header.y * 2u - 1u || proposal.y != 48u
            || !isfinite(globalFraction) || globalFraction < 0.125 || globalFraction > 1.0) return 0u;
    return words == 2u + header.y * EMITTER_TABLE_RECORD_WORDS + header.z + proposal.x * 3u ? header.y : 0u;
}

BlockEmitterRecord blockEmitterRecord(StructuredBuffer<uint4> table, uint index) {
    uint base = 2u + index * EMITTER_TABLE_RECORD_WORDS;
    BlockEmitterRecord record;
    record.corner = asfloat(table[base]);
    record.edge1 = asfloat(table[base + 1u]);
    record.edge2 = asfloat(table[base + 2u]);
    record.uvCorner = asfloat(table[base + 3u]);
    record.uvEdges = asfloat(table[base + 4u]);
    record.tint = asfloat(table[base + 5u]);
    return record;
}

uint blockEmitterId(StructuredBuffer<uint4> table, uint index) {
    return table[2u + index * EMITTER_TABLE_RECORD_WORDS + 6u].x;
}

uint blockEmitterFind(StructuredBuffer<uint4> table, uint id) {
    uint count = blockEmitterCount(table);
    if (id == 0u || count == 0u) return 0xFFFFFFFFu;
    uint capacity = table[0].z;
    uint slot = (id * 0x9E3779B9u) & (capacity - 1u);
    uint start = 2u + count * EMITTER_TABLE_RECORD_WORDS;
    for (uint probe = 0u; probe < capacity; ++probe) {
        uint2 entry = table[start + slot].xy;
        if (entry.x == 0u) break;
        if (entry.x == id) {
            return entry.y < count && blockEmitterId(table, entry.y) == id ? entry.y : 0xFFFFFFFFu;
        }
        slot = (slot + 1u) & (capacity - 1u);
    }
    return 0xFFFFFFFFu;
}

// Affine quad coordinates; independent projections are wrong for skewed quads and ignore displacement off the plane.
bool blockEmitterCoordinates(float3 point, BlockEmitterRecord emitter, float3 cameraRelative, out float2 uv) {
    uv = float2(0.0);
    float3 delta = point - (emitter.corner.xyz - cameraRelative);
    float aa = dot(emitter.edge1.xyz, emitter.edge1.xyz);
    float ab = dot(emitter.edge1.xyz, emitter.edge2.xyz);
    float bb = dot(emitter.edge2.xyz, emitter.edge2.xyz);
    float determinant = aa * bb - ab * ab;
    if (!isfinite(determinant) || determinant <= max(aa * bb * 1.0e-6, 1.0e-16)) return false;
    float da = dot(delta, emitter.edge1.xyz), db = dot(delta, emitter.edge2.xyz);
    uv = float2(da * bb - db * ab, db * aa - da * ab) / determinant;
    float3 residual = delta - emitter.edge1.xyz * uv.x - emitter.edge2.xyz * uv.y;
    float tolerance = 1.0e-4 + max(length(delta), length(emitter.corner.xyz - cameraRelative)) * 2.0e-6;
    return all(isfinite(uv)) && all(uv >= 0.0) && all(uv <= 1.0)
        && dot(residual, residual) <= tolerance * tolerance;
}
