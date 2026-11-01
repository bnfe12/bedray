// ReSTIR PT temporal stage for the Slang path tracer: the live adapter over experimental/restir_pt.slang.
//
// The reservoir math (admission, change of measure, merge, resolve) lives in the module; this file decides what one pixel
// hands it.  The tracer records the first edge of its first path (pathtrace/path_tracer.slang, PathPtRecord) and the
// entry calls ptTemporalStage once per pixel with that path.  Three rules, each pinned by RestirPtTemporalStageTest:
//
//   1. Every draw of an eligible receiver is one attempt.  A candidate enters through ptAddPath; anything else - a specular
//      lobe, a truncation before the first vertex, a sky or emitter first edge, an uncertified first vertex, a candidate
//      the add refused - enters through ptAddEmptyAttempt.  M counts draws from the first-edge density, never recordings,
//      or W = wSum / (M * p-hat) is scaled by 1/P(recorded) (docs/RESTIR_GI_DERIVATION.md section 6).
//   2. Ownership.  The reservoir owns exactly the diffuse continuation of the primary vertex whose first scattered vertex
//      is certified diffuse and does not emit.  An admitted candidate's suffix is taken out of the path and comes back as
//      f_d * cos * L * W; every other term stays where the path put it.  So nothing is counted twice and nothing is
//      dropped: the sky and emitter first edges the derivation refuses to transport are still in the picture, owned by
//      the path that traced them, and DI's next-event estimate at the primary vertex is not touched at all.
//   3. First-frame identity.  With no history M = 1 and W = 1/q, so the resolved term equals the suffix removed and the
//      image is the PT-off image (up to the module's documented firefly caps).
//
// History: one RestirPtReservoir per pixel and parity half, the layout of the DI ring (`parity * pixels + row * width +
// col`).  The record's own anchor is the stored receiver, so the history needs no second position/normal/depth buffer: a
// pixel reprojects its receiver (position + its own motion + the camera delta, the DI convention) into the previous frame,
// reads that pixel's record from the other half and validates it through contracts/history.slang on the camera-relative
// positions of the frame that wrote it.  SurfaceHit.worldPosition is never a key: it wraps at 1024 blocks (audit P1-6).
//
// Approximations, all documented in the derivation's "Live temporal stage" section: the certificate reads a rough
// dielectric's residual GGX lobe as Lambertian; the suffix stored with a vertex was shaded with the old edge's view; a
// first vertex on a moving entity is rebased with the camera delta only; and the module's candidate/resolve caps and the
// occlusion decay are firefly/lag guards, not part of the estimator.

public module pt_temporal;

import "contracts/contracts.slang";
__exported import "experimental/restir_pt.slang";
import "pathtrace/path_tracer.slang";
import "restir/di_block_emitters.slang";
import "sampling.slang";
import "surface/surface.slang";
import "traversal/traversal.slang";

// Bits of PathTracePush.flags next to PATH_TRACE_OPTION_RESTIR_PT (bit 17).  The PT ring has its own parity and history
// because it does not need emitters: a daylight frame with an empty emitter list runs no DI at all, and borrowing DI's
// parity would freeze it there.  The camera bits are repeated for the same reason - DI's copies are only written while
// DI runs.
static const uint PATH_TRACE_PT_HISTORY = 1u << 18u;   // the other half holds records of this convention and extent
static const uint PATH_TRACE_PT_PARITY  = 1u << 19u;   // the half this dispatch writes
static const uint PATH_TRACE_PT_STATIC  = 1u << 20u;   // the camera did not move: the history is the same pixel
static const uint PATH_TRACE_PT_FLIP_Y  = 1u << 21u;   // the reprojection mirrors the previous-frame y

// Receiver validation of a reprojected record: the DI bounds (a 5 % relative depth band, ~37 degrees of normal turn on a
// still camera and ~53 while moving), expressed as a HistoryPolicy so historyValidate stays the one definition of valid.
static const float PT_HISTORY_DEPTH_TOLERANCE = 0.05;
static const float PT_HISTORY_NORMAL_STATIC = 0.8;
static const float PT_HISTORY_NORMAL_MOVING = 0.6;
static const float PT_HISTORY_MAX_AGE = 32.0;

struct PtTemporalState {
    uint   enabled;            // PATH_TRACE_OPTION_RESTIR_PT for this dispatch
    uint   historyValid;       // PATH_TRACE_PT_HISTORY
    uint   cameraStatic;
    uint   flipY;
    uint   parity;
    int2   pixel;
    uint2  size;
    uint   frameIndex;
    float3 cameraDelta;        // raw camera position of this frame minus the previous one
    float4x4 previousViewProjection;
};

uint ptReservoirSlot(int2 pixel, uint2 size, uint parity) {
    return parity * size.x * size.y + uint(pixel.y) * size.x + uint(pixel.x);
}

// Records the host bound.  A host without a ring (the native core, or the switch off) binds one record, so the slot test
// below turns the stage off instead of writing past the binding.
uint ptReservoirCount(RWStructuredBuffer<RestirPtReservoir> reservoirs) {
    uint count;
    uint stride;
    reservoirs.GetDimensions(count, stride);
    return count;
}

bool ptTemporalActive(PtTemporalState state, RWStructuredBuffer<RestirPtReservoir> reservoirs) {
    return state.enabled != 0u && uint(state.pixel.x) < state.size.x && uint(state.pixel.y) < state.size.y
        && ptReservoirSlot(state.pixel, state.size, 1u) < ptReservoirCount(reservoirs);
}

// The module's stable sample identity: the pixel and the low byte of the frame, offset by one so 0 stays "unknown".  The
// frame byte rather than a parity bit: a pixel's own sample can survive in its history for more than one frame, and with
// a parity bit it would collide with this frame's draw two frames later and be dropped as a duplicate.  A record older
// than 256 frames is past PT_HISTORY_MAX_AGE long before its byte repeats.  2^23 pixels (4K) << 8 still fits 31 bits.
uint ptSampleId(int2 pixel, uint2 size, uint frameIndex) {
    return (((uint(pixel.y) * size.x + uint(pixel.x)) << 8u) | (frameIndex & 0xFFu)) + 1u;
}

// The stored record's own receiver as the history contract's sample: its anchor and normal in the frame that wrote it,
// its age, and that frame's camera distance as the depth.
HistorySample ptStoredReceiver(RestirPtReservoir stored) {
    HistorySample sample = emptyHistorySample();
    sample.normalRough = float4(stored.sample.anchorNormal, 1.0);
    sample.positionDepth = float4(stored.sample.anchorPosition, length(stored.sample.anchorPosition));
    sample.meta = uint4(stored.source.x, uint(HistoryStatus.Valid), 0u, 0u);
    return sample;
}

// The receiver a record describes, written into a record that won no sample.  A record with a sample already carries this
// receiver as its anchor (ptRecordPath, ptShiftPath), so it is left alone; an attempts-only record (M > 0, no weight)
// would otherwise keep the zero anchor of ptEmptyReservoir, and the spatial stage could not hand that pixel a
// neighbour's path.  The anchor is data only: ptReservoirHasSample still reads the record as empty.
RestirPtReservoir ptAnchorReceiver(RestirPtReservoir reservoir, float3 position, float3 normal) {
    if (ptReservoirHasSample(reservoir)) return reservoir;
    reservoir.sample.anchorPosition = position;
    reservoir.sample.anchorNormal = safeNormalize(normal, float3(0.0, 1.0, 0.0));
    return reservoir;
}

// One pixel's temporal stage.  Returns the first path's radiance with the reservoir's share swapped in, and publishes the
// record the next frame reads.  `seedBase` is the path's own seed; the stage draws from a decorrelated stream so turning
// it on never changes the random numbers the path itself used.
float3 ptTemporalStage<T : ISceneTracer>(T traversal, MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        MaterialSamplingOptions materialOptions, float rayBias, PathTraceResult path, PtTemporalState state,
        RWStructuredBuffer<RestirPtReservoir> reservoirs, uint seedBase) {
    float3 radiance = path.radiance;
    if (!ptTemporalActive(state, reservoirs)) {
        return radiance;
    }
    uint slot = ptReservoirSlot(state.pixel, state.size, state.parity);
    PathPtRecord record = path.pt;
    uint flags = path.flags;
    if (record.status == PATH_PT_RECORD_NONE) {
        // Not a receiver this reservoir may add to (a sky pixel, water, an optical interface, ...): an empty record keeps
        // a stale history from being resurrected by the next frame's reprojection.
        reservoirs[slot] = ptEmptyReservoir();
        return radiance;
    }

    uint seed = pcgHash(seedBase ^ 0x6a09e667u);
    uint sampleId = ptSampleId(state.pixel, state.size, state.frameIndex);
    RestirPtReservoir current = ptEmptyReservoir();
    bool candidate = record.status == PATH_PT_RECORD_VERTEX
        && (record.vertexFlags & PATH_PT_VERTEX_CERTIFIED) != 0u
        && (record.vertexFlags & (PATH_PT_VERTEX_ENVIRONMENT | PATH_PT_VERTEX_EMISSIVE)) == 0u
        && !pathTraceSampleRejected(flags);
    bool admitted = false;
    if (candidate) {
        RestirPtFirstVertex first = ptEmptyFirstVertex();
        first.position = record.vertexPosition;
        first.normal = record.vertexNormal;
        first.throughput = record.suffix;
        first.pdf = record.sourcePdf;
        first.bsdfPdf = record.reversePdf;
        RestirPtPath candidatePath = ptRecordPath(record.anchorPosition, record.anchorNormal, first, true, false,
            sampleId);
        current = ptAddPath(current, candidatePath, 0u, seed);
        admitted = current.stats.x > 0.0;
        if (admitted) {
            // The suffix leaves the path; the reservoir carries it from here, so it is counted once.
            radiance -= record.suffixContribution;
        } else {
            // The module refused the record (a degenerate edge): the draw still happened.
            current = ptAddEmptyAttempt(current);
        }
    } else {
        current = ptAddEmptyAttempt(current);
    }

    if (state.historyValid != 0u) {
        float3 motion = path.primaryMotion.xyz;
        float3 cameraDelta = state.cameraDelta;
        float3 previous;
        float previousDistance;
        int2 historyPixel;
        if (diReprojectReceiver(record.anchorPosition + motion, cameraDelta,
                state.previousViewProjection, state.size, state.flipY != 0u, previous, previousDistance,
                historyPixel)) {
            uint historySlot = ptReservoirSlot(historyPixel, state.size, state.parity ^ 1u);
            if (historySlot < ptReservoirCount(reservoirs)) {
                RestirPtReservoir history = reservoirs[historySlot];
                RestirPtReceiver receiver;
                receiver.position = record.anchorPosition;
                receiver.historyPosition = previous;
                receiver.normal = record.anchorNormal;
                receiver.depth = previousDistance;
                receiver.materialId = 0u;
                HistoryPolicy policy = defaultHistoryPolicy();
                policy.maxAge = PT_HISTORY_MAX_AGE;
                policy.depthTolerance = PT_HISTORY_DEPTH_TOLERANCE;
                policy.normalTolerance = state.cameraStatic != 0u ? PT_HISTORY_NORMAL_STATIC : PT_HISTORY_NORMAL_MOVING;
                // A stored vertex lives in the camera-relative space of the frame that wrote it; this frame's space is
                // that one minus the camera delta.
                current = ptTemporalReuse(current, history, receiver, ptStoredReceiver(history), -cameraDelta,
                    policy, false, seed);
            }
        }
    }

    // A fresh winner is this frame's own edge: the path already traced it, so it needs no visibility ray, and it is
    // shaded along the direction it was sampled in, which makes f * cos * W exactly the bsdf weight it replaced.  A
    // reused winner is shaded along the edge from this receiver and re-traced through the filtered visibility walk.
    bool fresh = admitted && current.sample.first.sampleId == sampleId && current.source.x == 0u;
    RestirPtReceiver shading;
    shading.position = record.anchorPosition;
    shading.historyPosition = record.anchorPosition;
    shading.normal = record.anchorNormal;
    shading.depth = length(record.anchorPosition);
    shading.materialId = 0u;
    RestirPtResolveResult resolved = fresh
        ? ptResolveDetailed(current, record.anchorPosition, record.anchorNormal)
        : ptResolveFiltered(current, traversal, atlas, blockMaterials, shading, rayBias, materialOptions);
    float3 shadeDirection = fresh ? record.direction : resolved.direction;
    if (resolved.valid) {
        radiance += record.diffuseReflectance
            * (INV_PI * max(dot(record.anchorNormal, shadeDirection), 0.0))
            * resolved.radiance * resolved.weight;
    } else if (!fresh && ptReservoirHasSample(current)
            && (resolved.flags & RESTIR_PT_RESOLVE_FLAG_VISIBILITY_TRACED) != 0u) {
        // A reused edge that a new blocker cut: keep the sample and fade its weight, so the record recovers if the
        // blocker leaves and dies out if it stays.
        current = ptDecayOnOcclusion(current, true);
    }
    current = ptAnchorReceiver(current, record.anchorPosition, record.anchorNormal);
    reservoirs[slot] = current;
    return radiance;
}
