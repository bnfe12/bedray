// Sodium compact vertices -> planar scene arena. This is a data-format
// boundary: positions/UV/colour/light must match SodiumMeshDecoder and every
// BLAS/section-table consumer. One invocation owns all four vertices of a quad.
public module sodium_dequantize_pass;

struct DecodePush {
    uint64_t rawAddress;
    uint64_t arenaAddress;
    uint64_t flagsAddress;
    uint quadCount;
    uint reserved;
};
[[vk::push_constant]] DecodePush pc;

float decodeAxis(uint high, uint low, uint axis) {
    uint shift = axis * 10u;
    uint quantized = (((high >> shift) & 1023u) << 10u) | ((low >> shift) & 1023u);
    return float(quantized) * (32.0 / 1048576.0) - 8.0;
}

float decodeUv(uint lane) {
    float magnitude = float(lane & 0x7fffu) * (1.0 / 32768.0);
    return magnitude + ((lane & 0x8000u) != 0u ? 1.0 : -1.0) * (1.0 / 32768.0);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    uint q = tid.x;
    if (q >= pc.quadCount) return;
    uint* source = (uint*)pc.rawAddress;
    uint* arena = (uint*)pc.arenaAddress;
    uint flags = pc.flagsAddress != uint64_t(0) ? ((uint*)pc.flagsAddress)[q] : 0u;
    uint uvBase = pc.quadCount * 12u;
    uint colourBase = pc.quadCount * 20u;
    uint lightBase = pc.quadCount * 24u;
    for (uint vertex = 0u; vertex < 4u; ++vertex) {
        uint input = q * 20u + vertex * 5u;
        uint output = q * 4u + vertex;
        uint high = source[input];
        uint low = source[input + 1u];
        for (uint axis = 0u; axis < 3u; ++axis)
            arena[output * 3u + axis] = asuint(decodeAxis(high, low, axis));
        uint uv = source[input + 3u];
        arena[uvBase + output * 2u] = asuint(decodeUv(uv));
        arena[uvBase + output * 2u + 1u] = asuint(decodeUv(uv >> 16u));
        arena[colourBase + output] = source[input + 2u];
        arena[lightBase + output] = (source[input + 4u] & 0x00ffffffu) | flags;
    }
}
