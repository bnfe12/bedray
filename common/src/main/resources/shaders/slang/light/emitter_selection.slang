public module emitter_selection;
__exported import "light/emitter_table.slang";

static const uint EMITTER_BVH_MAX_DEPTH = 16u;

float blockEmitterIndexProbability(uint index, uint count) {
    return (sqrt(float(index + 1u)) - sqrt(float(index))) * rsqrt(float(count));
}

uint emitterBvhStart(StructuredBuffer<uint4> table, uint count) {
    return 2u + count * EMITTER_TABLE_RECORD_WORDS + table[0].z;
}

float emitterNodeImportance(StructuredBuffer<uint4> table, uint start, uint node, float3 receiver) {
    float4 minimum = asfloat(table[start + node * 3u]);
    float3 maximum = asfloat(table[start + node * 3u + 1u]).xyz;
    float3 distance = max(max(minimum.xyz - receiver, receiver - maximum), float3(0.0));
    return minimum.w / (1.0 + dot(distance, distance));
}

float emitterLeftProbability(StructuredBuffer<uint4> table, uint start, uint2 children, float3 receiver) {
    float left = emitterNodeImportance(table, start, children.x, receiver);
    float right = emitterNodeImportance(table, start, children.y, receiver);
    float sum = left + right;
    return sum > 0.0 && isfinite(sum) ? left / sum : 0.5;
}

// Negative means a malformed topology, not a valid zero-probability branch.
float emitterLocalProbability(StructuredBuffer<uint4> table, uint index, float3 receiver, uint count) {
    uint start = emitterBvhStart(table, count), nodes = table[1].x;
    uint node = table[2u + index * EMITTER_TABLE_RECORD_WORDS + 6u].y;
    float probability = 1.0;
    for (uint level = 0u; level <= EMITTER_BVH_MAX_DEPTH; ++level) {
        if (node >= nodes) return -1.0;
        uint4 link = table[start + node * 3u + 2u];
        if (level == 0u && link.w != index) return -1.0;
        if (link.z == 0xFFFFFFFFu) return node == 0u ? probability : -1.0;
        if (link.z >= nodes) return -1.0;
        uint2 children = table[start + link.z * 3u + 2u].xy;
        if (any(children >= nodes)) return -1.0;
        float left = emitterLeftProbability(table, start, children, receiver);
        if (node == children.x) probability *= left;
        else if (node == children.y) probability *= 1.0 - left;
        else return -1.0;
        node = link.z;
    }
    return -1.0;
}

float blockEmitterSelectionProbability(StructuredBuffer<uint4> table, uint index, float3 receiver) {
    uint count = blockEmitterCount(table);
    if (index >= count) return 0.0;
    float globalFraction = asfloat(table[1].z);
    float local = globalFraction == 1.0 ? 0.0 : emitterLocalProbability(table, index, receiver, count);
    return local < 0.0 ? 0.0 : globalFraction * blockEmitterIndexProbability(index, count) + (1.0 - globalFraction) * local;
}

struct BlockEmitterSelection { uint index; float probability; };

// The global branch alone: index = floor(u^2 * count) over the importance-sorted list, with its exact PMF and no light
// tree walk.  Used at bounce vertices: in a torch room with 674 lights the tree (root-to-leaf to select, leaf-to-root for
// the mixture PMF) cost ~12 of 29 ms of trace at 2560x1440 (2026-11-08), and indirect light is soft enough that the
// coarser choice does not show.  The PMF is the one the RIS weight divides by, so the estimate stays unbiased.
BlockEmitterSelection blockEmitterSelectGlobal(StructuredBuffer<uint4> table, float draw) {
    BlockEmitterSelection selected = {0xFFFFFFFFu, 0.0};
    uint count = blockEmitterCount(table);
    if (count == 0u) return selected;
    float u = clamp(draw, 0.0, 0.99999994);
    selected.index = min(uint(u * u * float(count)), count - 1u);
    selected.probability = blockEmitterIndexProbability(selected.index, count);
    return selected;
}

// One uniform draw samples the complete mixture. The returned PMF includes both ways of selecting the same light.
BlockEmitterSelection blockEmitterSelect(StructuredBuffer<uint4> table, float3 receiver, float draw) {
    BlockEmitterSelection selected = {0xFFFFFFFFu, 0.0};
    uint count = blockEmitterCount(table);
    if (count == 0u) return selected;
    float globalFraction = asfloat(table[1].z);
    draw = clamp(draw, 0.0, 0.99999994);
    if (draw < globalFraction) {
        float u = draw / globalFraction;
        selected.index = min(uint(u * u * float(count)), count - 1u);
        selected.probability = blockEmitterSelectionProbability(table, selected.index, receiver);
        return selected;
    }
    float u = (draw - globalFraction) / (1.0 - globalFraction), probability = 1.0;
    uint start = emitterBvhStart(table, count), node = 0u, nodes = table[1].x;
    for (uint level = 0u; level <= EMITTER_BVH_MAX_DEPTH; ++level) {
        if (node >= nodes) return selected;
        uint4 link = table[start + node * 3u + 2u];
        if (link.w != 0xFFFFFFFFu) {
            if (link.w >= count || table[2u + link.w * EMITTER_TABLE_RECORD_WORDS + 6u].y != node)
                return selected;
            selected.index = link.w;
            selected.probability = globalFraction * blockEmitterIndexProbability(link.w, count) + (1.0 - globalFraction) * probability;
            return selected;
        }
        if (any(link.xy >= nodes)) return selected;
        float left = emitterLeftProbability(table, start, link.xy, receiver);
        if (u < left) { probability *= left; u /= left; node = link.x; }
        else { probability *= 1.0 - left; u = (u - left) / (1.0 - left); node = link.y; }
        u = clamp(u, 0.0, 0.99999994);
    }
    return selected;
}
