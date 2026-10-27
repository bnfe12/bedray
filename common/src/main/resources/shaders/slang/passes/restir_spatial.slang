// ReSTIR DI spatial-reuse entry for Bedray 2.0.
//
// One thread per render pixel, two dispatches per frame (one per checker colour): it pools the reservoirs the path
// trace pass just wrote, so the direct light of a surface is chosen from its neighbourhood and not from that pixel's
// own eight draws.  The math, the gates and the numbers live in `restir/spatial_block_emitters.slang`.
//
// This pass traces no rays and shades nothing: it only rewrites reservoirs, which the next frame's temporal import and
// this frame's display both read through the shading pass.
//
// Bindings mirror `passes/path_trace.slang` for every resource the two share, so the host binds the same views twice
// instead of repacking them:
//   3  albedoAtlas      combined image sampler
//   9  materialAtlas    combined image sampler (LabPBR _s)
//  10  normalAtlas      combined image sampler (LabPBR _n)
//  16  frame            ConstantBuffer<FrameConstants> (608 B) - only `camera.size` and `frame` are read
//  18  emitters         StructuredBuffer<BlockEmitterRecord> (96 B per record)
//  19  reservoirs       RWStructuredBuffer<DIReservoir> (two parity halves, 64 B per pixel)

public module restir_spatial_pass;

import "contracts/contracts.slang";
import "light/block_emitters.slang";
import "restir/di_block_emitters.slang";
import "restir/restir_di.slang";
import "restir/spatial_block_emitters.slang";
import "surface/surface.slang";

// Bit 0 of RestirSpatialPush.state.w: the pool-merge branch is a flag, not a second shader, so the host can A/B spatial
// reuse against temporal-only within the same frame graph.
static const uint RESTIR_SPATIAL_ENABLED = 1u << 0u;

struct RestirSpatialPush {
    uint4  state;          // x: frame index, y: parity half the trace wrote, z: checker phase, w: RESTIR_SPATIAL_* bits
    float4 cameraRelative; // xyz: camera minus the origin the emitter list was rebuilt at, w: reserved
    float  maxDistance;    // the trace pass's ray window, in blocks
    float3 pad;
};

[[vk::binding(3, 0)]] Sampler2D albedoAtlas;
[[vk::binding(9, 0)]] Sampler2D materialAtlas;
[[vk::binding(10, 0)]] Sampler2D normalAtlas;
[[vk::binding(16, 0)]] ConstantBuffer<FrameConstants> frame;
[[vk::binding(18, 0)]] StructuredBuffer<BlockEmitterRecord> emitters;
[[vk::binding(19, 0)]] RWStructuredBuffer<DIReservoir> reservoirs;

[[vk::push_constant]] RestirSpatialPush pc;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 tid : SV_DispatchThreadID) {
    FrameConstants f = frame;
    if (f.camera.size.x == 0u || f.camera.size.y == 0u) {
        return;   // an uninitialised frame block is a host bug; a zero ring index would be a device-side fault
    }
    int2 p = int2(tid.xy);
    uint2 size = f.camera.size.xy;
    if (p.x >= int(size.x) || p.y >= int(size.y)) {
        return;
    }

    MaterialAtlas atlas;
    atlas.albedo = albedoAtlas;
    atlas.material = materialAtlas;
    atlas.normal = normalAtlas;
    // atlas.entities is deliberately left empty: this pass reads block-emitter
    // texels and traces no ray, so no entity surface is ever decoded here.

    BlockEmitterSpatialReuse reuse;
    reuse.records = emitters;
    reuse.reservoirs = reservoirs;
    reuse.atlas = atlas;
    reuse.enabled = (pc.state.w & RESTIR_SPATIAL_ENABLED) != 0u ? 1u : 0u;
    reuse.phase = pc.state.z;
    reuse.parity = pc.state.y;
    reuse.frameIndex = pc.state.x;
    reuse.pixel = p;
    reuse.size = size;
    reuse.candidates = 1u;   // evaluate ignores it and this pass never draws a list; one keeps the sampler well-formed
    reuse.cameraRelative = pc.cameraRelative.xyz;
    reuse.maxDistance = max(pc.maxDistance, 1.0);
    reuse.run();
}
