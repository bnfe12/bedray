// ReSTIR PT spatial stage for the Slang path tracer: one pass after the trace, over the records the temporal stage wrote.
//
// Each pixel merges four neighbours' records into its own through the module's ptSpatialReuse (same-surface test,
// first-edge visibility through the filtered walk, the fixed-first-vertex shift Jacobian, the spatial ratio cap) and
// publishes the result into the OTHER half of the ring.  The host then keeps the parity, so the next frame's temporal
// import reads exactly this half (ReferencePathTraceBackend, RestirDiState.spatialPublished).  Reading one half and
// writing the other is what lets one dispatch run without a checker: no thread ever reads a slot another thread writes.
//
// The stage shades nothing.  A spatial winner reaches the image one frame later, through the temporal stage, which
// resolves every reused winner with ptResolveFiltered at its own receiver - the same lag the DI spatial pass has.
//
// Invariants, each pinned by RestirPtSpatialStageTest:
//   1. Four fixed, distinct neighbours per pixel, never the pixel itself (ptSpatialReuse cannot tell a duplicate draw
//      from a second sample, so the caller owns distinctness).
//   2. A pixel that is no receiver, or whose neighbours all fail, publishes its own record unchanged, so the next import
//      sees at least what temporal-only reuse would have handed it.
//   3. The receiver is the record's own anchor: the temporal stage stores it even when the pixel won no sample
//      (ptAnchorReceiver), so such a pixel can still take a neighbour's path.
//
// Approximation, documented in the derivation's "Live spatial stage" section: the merge has no MIS between the pooled
// domains (the module's spatial ratio cap and the visibility test stand in for it), so the pooled estimate is the
// biased-but-consistent form the module was written for, not the unbiased pairwise-MIS form.

public module pt_spatial;

import "contracts/contracts.slang";
__exported import "restir/pt_temporal.slang";
import "sampling.slang";
import "surface/surface.slang";
import "traversal/traversal.slang";

static const uint PT_SPATIAL_NEIGHBOURS = 4u;
// A rotated cross with a growing radius: four points 90 degrees apart on rings of 2.5, 4.5, 6.5 and 8.5 pixels.  Any two
// of them are at least sqrt(2.5^2 + 4.5^2) > 5 pixels apart before rounding moves each by at most 0.71, so they are
// always four different pixels, and the smallest ring keeps the pixel itself out.
static const float PT_SPATIAL_RADIUS_BASE = 2.5;
static const float PT_SPATIAL_RADIUS_STEP = 2.0;

int2 ptSpatialOffset(uint i, float rotation) {
    float radius = PT_SPATIAL_RADIUS_BASE + PT_SPATIAL_RADIUS_STEP * float(i);
    float angle = rotation + float(i) * (0.5 * PI);
    return int2(int(round(radius * cos(angle))), int(round(radius * sin(angle))));
}

// Any pixel the temporal stage counted an attempt for: it wrote its anchor whether or not a sample won.  A NONE pixel (sky,
// water, an optical interface) carries M = 0 and is not a receiver.
bool ptSpatialReceiver(RestirPtReservoir reservoir) {
    return isFinite(reservoir.stats.x) && reservoir.stats.x > 0.0
        && isFinite(reservoir.sample.anchorPosition) && dot(reservoir.sample.anchorNormal, reservoir.sample.anchorNormal) > 0.25;
}

void ptSpatialStage<T : ISceneTracer>(T traversal, MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        MaterialSamplingOptions materialOptions, float bias, RWStructuredBuffer<RestirPtReservoir> reservoirs,
        int2 pixel, uint2 size, uint parity, uint frameIndex) {
    if (uint(pixel.x) >= size.x || uint(pixel.y) >= size.y) {
        return;
    }
    // The same bound test the temporal stage uses: a one-record stand-in turns the pass off instead of being overrun.
    if (ptReservoirSlot(pixel, size, 1u) >= ptReservoirCount(reservoirs)) {
        return;
    }
    uint readSlot = ptReservoirSlot(pixel, size, parity);
    uint writeSlot = ptReservoirSlot(pixel, size, parity ^ 1u);
    RestirPtReservoir current = reservoirs[readSlot];
    if (!ptSpatialReceiver(current)) {
        reservoirs[writeSlot] = current;
        return;
    }

    RestirPtReceiver receiver;
    receiver.position = current.sample.anchorPosition;
    receiver.historyPosition = current.sample.anchorPosition;
    receiver.normal = current.sample.anchorNormal;
    receiver.depth = length(current.sample.anchorPosition);
    receiver.materialId = 0u;

    // Its own stream: the trace pass's seeds hash the pixel with other constants, so the rotation is decorrelated from the
    // paths it pools.
    uint seed = pcgHash((uint(pixel.y) * size.x + uint(pixel.x)) ^ pcgHash(frameIndex ^ 0xbb67ae85u));
    float rotation = rand01(seed) * TWO_PI;
    for (uint i = 0u; i < PT_SPATIAL_NEIGHBOURS; ++i) {
        int2 neighbourPixel = pixel + ptSpatialOffset(i, rotation);
        if (neighbourPixel.x < 0 || neighbourPixel.y < 0
            || neighbourPixel.x >= int(size.x) || neighbourPixel.y >= int(size.y)) {
            continue;
        }
        RestirPtReservoir neighbour = reservoirs[ptReservoirSlot(neighbourPixel, size, parity)];
        current = ptSpatialReuse(current, neighbour, receiver, traversal, atlas, blockMaterials, bias, materialOptions, seed);
    }
    reservoirs[writeSlot] = current;
}
