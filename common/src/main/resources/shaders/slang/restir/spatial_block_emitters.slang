// ReSTIR DI spatial reuse for the Slang path tracer.
//
// This module is the second reuse stage over the records `di_block_emitters.slang` writes: a pixel draws a handful of
// neighbouring reservoirs, re-resolves each one at *its own* surface and merges it with the same RIS primitive the
// temporal stage uses, so one torch lights a wall from fewer, better-chosen samples instead of from eight draws of its
// own list.  The numbers - the eight draws, the screen radius, the normal and plane gates, the caps - are the ones
// `rt_restir_spatial.comp` has been running on GPUs with, pinned against it by `RtSlangRestirSpatialTest`.
//
// No rays are traced here: a reservoir is a sample distribution, and visibility stays the shading pass's job (the
// temporal stage decays an occluded record through `reuseConfirm`).  A neighbour's record therefore only has to prove
// that it belongs to the same surface, which is what the two gates below decide.
//
// Space: both readers and writers live in the frame half the trace pass just wrote, so every position in a record is
// already in this frame's camera-anchored space and no reprojection or `cameraDelta` term is involved.  The tolerance
// below therefore measures the camera distance out of the stored receiver position itself, exactly as the temporal
// import measures the reprojected one.
//
// Two dispatches per frame, one per checker colour: a pixel of colour `phase` writes its own slot and reads only cells
// of the other colour, so the cells written by this pass are never cells another thread still reads - the update is
// in-place and race-free without a second ring.  The second dispatch re-reads what the first wrote (iterated reuse).

public module spatial_block_emitters;

import "contracts/light.slang";
import "contracts/math.slang";
import "light/block_emitters.slang";
import "restir/di_block_emitters.slang";
import "restir/restir_di.slang";
import "sampling.slang";
import "surface/surface.slang";

// The legacy kernel draws eight candidates from a square of +/- 7 pixels and forces the offset to the opposite checker
// colour, which keeps the effective radius at eight while staying off the cells this pass writes.
static const int SPATIAL_OFFSET_RADIUS = 7;
static const uint SPATIAL_DRAWS = 8u;
static const float SPATIAL_NORMAL_DOT_MIN = 0.85;
static const float SPATIAL_PLANE_TOLERANCE_RELATIVE = 0.05;
static const float SPATIAL_PLANE_TOLERANCE_ABSOLUTE = 0.1;

// Same hash as the legacy kernel, so a side-by-side frame can be compared sample for sample.
uint blockEmitterSpatialSeed(int2 pixel, uint frameIndex) {
    return pcgHash(uint(pixel.x) * 1973u + uint(pixel.y) * 9277u + frameIndex * 26699u + 7u);
}

bool blockEmitterSpatialWriter(int2 pixel, uint phase) {
    return ((pixel.x + pixel.y) & 1) == int(phase);
}

// The neighbour offset of one draw: uniform per axis in [-radius, radius], pushed onto the opposite checker colour by
// shifting x by one.  Returning the offset instead of the slot keeps the wrap test with the caller.
int2 blockEmitterSpatialOffset(inout uint seed, int radius) {
    float2 u = float2(rand01(seed), rand01(seed));
    int2 offset = int2(int(round(u.x * float(2 * radius))) - radius,
                       int(round(u.y * float(2 * radius))) - radius);
    if (((offset.x + offset.y) & 1) == 0) {
        offset.x += offset.x >= 0 ? 1 : -1;
    }
    return offset;
}

struct BlockEmitterSpatialReuse {
    StructuredBuffer<BlockEmitterRecord> records;
    RWStructuredBuffer<DIReservoir> reservoirs;
    MaterialAtlas atlas;
    uint   enabled;
    uint   phase;                  // the checker colour this dispatch writes
    uint   parity;                 // the frame half the trace pass just wrote
    uint   frameIndex;
    int2   pixel;
    uint2  size;
    uint   candidates;
    float3 cameraRelative;
    float  maxDistance;

    BlockEmitterSampler emitters() {
        BlockEmitterSampler sampler;
        sampler.records = records;
        sampler.atlas = atlas;
        sampler.candidates = candidates;
        sampler.cameraRelative = cameraRelative;
        sampler.maxDistance = maxDistance;
        return sampler;
    }

    bool active() {
        return enabled != 0u && blockEmitterCount(records) > 0u
            && uint(pixel.x) < size.x && uint(pixel.y) < size.y
            && diReservoirSlot(pixel, size, parity) < diReservoirCount(reservoirs);
    }

    // A pixel merges only when it has a record of this frame: an empty slot still holds the *previous* frame's receiver
    // (the ring is two halves, and the trace pass writes only the pixels whose primary ray hit something), so merging it
    // would pool a surface that left the screen.
    bool ownRecord(DIReservoir own) {
        return min(own.stats.x, RESTIR_DI_M_CAP) > 0.0 && isFinite(own.sample.xyz) && isFinite(own.receiver.xyz);
    }

    bool neighbourUsable(DIReservoir neighbour) {
        return min(neighbour.stats.x, RESTIR_DI_M_CAP) > 0.0
            && neighbour.source.z == uint(LightKind.Emitter)
            && isFinite(neighbour.sample.xyz) && isFinite(neighbour.receiver.xyz);
    }

    // The merge of one pixel: read own record, rebuild the surface it belongs to out of its receiver lanes, pool the
    // neighbours into it and publish - or leave the pre-spatial record standing when nothing was won or the winner is not
    // finite.  A non-finite weight does not only break this pixel: next frame's temporal import reads this slot.
    void run() {
        if (!active() || !blockEmitterSpatialWriter(pixel, phase)) {
            return;
        }
        uint ownSlot = diReservoirSlot(pixel, size, parity);
        DIReservoir own = reservoirs[ownSlot];
        if (!ownRecord(own)) {
            return;
        }

        float3 position = own.receiver.xyz;
        float3 normal = reservoirReceiverNormal(own);
        float emission = (uint(own.stats.w + 0.5) & DI_RECEIVER_SELF_EMISSIVE) != 0u ? 1.0 : 0.0;
        LightReceiver receiver = lightReceiver(position, normal, emission);
        receiver.primary = true;
        // The plane gate is a surface test, not a distance test: two cells of the same wall share a plane however far
        // the wall is, and the legacy tolerance grows with the camera distance because a distant wall is denser in pixels.
        float planeTolerance = SPATIAL_PLANE_TOLERANCE_RELATIVE * max(length(position), 1.0)
            + SPATIAL_PLANE_TOLERANCE_ABSOLUTE;

        uint seed = blockEmitterSpatialSeed(pixel, frameIndex);
        DIReservoir merged = own;
        bool won = false;
        for (uint draw = 0u; draw < SPATIAL_DRAWS; draw += 1u) {
            int2 offset = blockEmitterSpatialOffset(seed, SPATIAL_OFFSET_RADIUS);
            // The shift that forced the opposite colour can push the offset one cell past the radius; the legacy kernel
            // accepts that (it stays inside the same 15x15 window) and the clamp below is a bounds test, not a shape test.
            int2 neighbourPixel = pixel + offset;
            if (neighbourPixel.x < 0 || neighbourPixel.y < 0
                || neighbourPixel.x >= int(size.x) || neighbourPixel.y >= int(size.y)) {
                continue;
            }
            uint neighbourSlot = diReservoirSlot(neighbourPixel, size, parity);
            if (neighbourSlot >= diReservoirCount(reservoirs)) {
                continue;
            }
            DIReservoir neighbour = reservoirs[neighbourSlot];
            if (!neighbourUsable(neighbour)) {
                continue;
            }
            if (dot(reservoirReceiverNormal(neighbour), normal) < SPATIAL_NORMAL_DOT_MIN) {
                continue;   // walls must not import each other's light
            }
            if (abs(dot(neighbour.receiver.xyz - position, normal)) > planeTolerance) {
                continue;   // a light sample behind a step stays local
            }
            merged = restirDiSpatial(emitters(), merged, neighbour, receiver,
                RESTIR_DI_M_CAP, RESTIR_DI_SPATIAL_RATIO_CAP, seed);
            if (!diSamePosition(merged.sample.xyz, own.sample.xyz)) {
                won = true;
            }
        }

        if (!won) {
            return;   // no neighbour won the roulette: the pre-spatial record keeps its own accumulated weight
        }
        if (!(merged.stats.y >= 0.0) || !(merged.stats.x > 0.0) || !(merged.sample.w > 0.0)
            || !isFinite(merged.sample.xyz) || !isFinite(merged.stats.xyz)) {
            return;
        }
        // The receiver lanes describe the surface this pixel shaded, so they stay this pixel's even when the sample came
        // from a neighbour; the merge primitive never writes them.
        merged.receiver = own.receiver;
        merged.stats.w = own.stats.w;
        reservoirs[ownSlot] = merged;
    }
};
