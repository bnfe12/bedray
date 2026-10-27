// ReSTIR DI temporal reuse for the Slang path tracer's block-emitter NEE.
//
// The reservoir math lives in restir_di.slang; this module is the block-emitter adapter: the initial RIS batch, the
// reprojected history merge, the record writeback and the occlusion decay.  The numbers (M cap, ratio caps, decay,
// receiver validation) are the ones rt_common_emitters.glsl has been running on GPUs with.
//
// Space: a record stores the light point and the receiver in the camera-anchored space of the frame that wrote it, the
// same space the path tracer shades in.  `cameraDelta` is the raw camera delta between two frames, so a stored *light*
// point transforms exactly (`- cameraDelta` on import) and the stored *receiver* is only used to prove that this pixel
// still hits the surface the record belongs to.  The view bob is inside both receivers and differs frame to frame by a
// few centimetres, far below the tolerance below, which is why it needs no term of its own - the same convention
// path_display.slang already reprojects the running mean with.
//
// Spatial reuse runs as its own pass over these same records (`restir/spatial_block_emitters.slang`), which is why the
// receiver lanes and the receiver's own-emission bit are stored rather than recomputed by the reader.
//
// The two-call split (store in `reuseSample`, decay in `reuseConfirm`) exists because the path tracer owns the shadow
// ray: an occluded frame must keep its sample and decay its weight instead of dropping the record.

public module di_block_emitters;

import "contracts/light.slang";
import "contracts/math.slang";
import "light/block_emitters.slang";
import "restir/restir_di.slang";
import "surface/surface.slang";

// Receiver validation, the legacy bounds: a stationary camera may keep a history whose receiver distance differs by
// 5% + 0.1 blocks and whose normal turned by up to ~53 degrees; a moving camera has to prove the match, so both tighten.
static const float DI_DISTANCE_TOLERANCE_RELATIVE = 0.05;
static const float DI_DISTANCE_TOLERANCE_ABSOLUTE = 0.1;
static const float DI_NORMAL_DOT_STATIC = 0.8;
static const float DI_NORMAL_DOT_MOVING = 0.6;

// The ring is `parity * pixels + row * width + col`: two halves, so the frame being written never aliases the frame
// being read.  The host zeroes the buffer when the light list or the render size changes.
uint diReservoirSlot(int2 pixel, uint2 size, uint parity) {
    return parity * size.x * size.y + uint(pixel.y) * size.x + uint(pixel.x);
}

// Records the host bound.  A host that has the reservoir branch off binds a dummy of one record, so this is 1 and every
// slot check below still keeps the shader inside the buffer it was given.
uint diReservoirCount(RWStructuredBuffer<DIReservoir> reservoirs) {
    uint count;
    uint stride;
    reservoirs.GetDimensions(count, stride);
    return count;
}

// Exact position equality, used to tell whether the reservoir still holds this frame's batch winner after the merge.
// The merge copies a stored position verbatim, so no epsilon is involved.
bool diSamePosition(float3 a, float3 b) {
    return a.x == b.x && a.y == b.y && a.z == b.z;
}

// Where the surface this pixel hit now sat in the previous frame, and the pixel that saw it there.  A failed test is a
// disocclusion or an off-screen pixel and simply means "no history".
bool diReprojectReceiver(float3 receiverPosition, float3 cameraDelta, float4x4 previousViewProjection, uint2 size,
        bool flipY, out float3 previous, out float previousDistance, out int2 historyPixel) {
    previous = receiverPosition + cameraDelta;
    previousDistance = length(previous);
    historyPixel = int2(-1, -1);
    float4 clip = mul(previousViewProjection, float4(previous, 1.0));
    if (!(clip.w > 1.0e-4)) {
        return false;
    }
    float2 ndc = clip.xy / clip.w;
    if (flipY) {
        ndc.y = -ndc.y;
    }
    int2 q = int2(floor((ndc * 0.5 + 0.5) * float2(size)));
    if (q.x < 0 || q.y < 0 || q.x >= int(size.x) || q.y >= int(size.y)) {
        return false;
    }
    historyPixel = q;
    return true;
}

// Reads the reprojected record and proves that it describes the surface now shading this pixel: it must be a block
// emitter, its receiver must be within the legacy distance and position tolerance and its normal must not have turned
// past the cut-off.  The light point of an accepted record is rebased into *this* frame, so the record written after the
// merge describes the frame it was written in.
bool diLoadHistory(RWStructuredBuffer<DIReservoir> reservoirs, int2 historyPixel, uint2 size, uint parity,
        float3 previousReceiver, float previousDistance, float3 receiverNormal, bool cameraStatic,
        float3 cameraDelta, inout DIReservoir history) {
    history = emptyReservoir();
    uint slot = diReservoirSlot(historyPixel, size, parity ^ 1u);
    if (slot >= diReservoirCount(reservoirs)) {
        return false;
    }
    DIReservoir stored = reservoirs[slot];
    if (!(min(stored.stats.x, RESTIR_DI_M_CAP) > 0.0) || !isFinite(stored.sample.xyz)
        || !isFinite(stored.receiver.xyz)) {
        return false;
    }
    if (stored.source.z != uint(LightKind.Emitter)) {
        return false;   // this adapter only reuses block emitters
    }
    float tolerance = DI_DISTANCE_TOLERANCE_RELATIVE * max(previousDistance, 1.0) + DI_DISTANCE_TOLERANCE_ABSOLUTE;
    // The stored receiver and `previousReceiver` are both in the frame that wrote them, so they need no rebase.
    if (abs(length(stored.receiver.xyz) - previousDistance) > tolerance) {
        return false;
    }
    if (length(stored.receiver.xyz - previousReceiver) > tolerance) {
        return false;
    }
    float normalDot = dot(reservoirReceiverNormal(stored), receiverNormal);
    if (normalDot < (cameraStatic ? DI_NORMAL_DOT_STATIC : DI_NORMAL_DOT_MOVING)) {
        return false;
    }
    stored.sample.xyz -= cameraDelta;
    history = stored;
    return true;
}

// One pixel's direct-light reservoir: the batch it draws this frame, the history it reuses and the record the next frame
// reads.  Implements IReuseSampler, so path_tracer.slang never names ReSTIR, the emitter list or a buffer.
struct BlockEmitterDiSampler : IReuseSampler {
    StructuredBuffer<BlockEmitterRecord> records;
    RWStructuredBuffer<DIReservoir> reservoirs;
    MaterialAtlas atlas;
    uint   candidates;
    float3 cameraRelative;            // camera position minus the origin the emitter list was rebuilt at
    float  maxDistance;
    // Screen-space addressing of this thread's own slot, plus the frame state a temporal merge needs.
    uint   enabled;
    uint   historyValid;              // the previous frame wrote records of this convention
    uint   cameraStatic;
    uint   flipY;
    uint   parity;
    int2   pixel;
    uint2  size;
    float3 cameraDelta;               // raw camera position of this frame minus the previous one
    float4x4 previousViewProjection;

    uint reservoirSlot() {
        return diReservoirSlot(pixel, size, parity);
    }

    // The plain sampler of the same list: a stored point is re-resolved through it, so a history import and a fresh
    // draw can never disagree about p-hat, pdf or Le.
    BlockEmitterSampler emitters() {
        BlockEmitterSampler sampler;
        sampler.records = records;
        sampler.atlas = atlas;
        sampler.candidates = candidates;
        sampler.cameraRelative = cameraRelative;
        sampler.maxDistance = maxDistance;
        return sampler;
    }

    // Every entry point addresses the pixel's own slot, so one guard covers the read, the write and the clear.
    bool reuseActive() {
        return enabled != 0u && candidates > 0u && blockEmitterCount(records) > 0u
            && uint(pixel.x) < size.x && uint(pixel.y) < size.y
            && reservoirSlot() < diReservoirCount(reservoirs);
    }

    // Draws the batch, merges the reprojected history into it, publishes the record and returns the sample the path
    // tracer will shade.  The published record carries its full weight: `reuseConfirm` applies the shadow ray after it.
    LightSample reuseSample(LightReceiver receiver, inout uint seed) {
        LightSample result = invalidLightSample();
        if (!reuseActive() || !receiver.primary) {
            return result;
        }

        BlockEmitterWinner winner = sampleBlockEmitterBatch(records, atlas, receiver, cameraRelative,
            maxDistance, candidates, seed);
        DIReservoir current = emptyReservoir();
        if (winner.hasWinner) {
            current.sample = float4(winner.candidate.point.position, winner.candidate.phat);
            current.stats = float4(winner.sampleCount, winner.weightSum, 1.0, 0.0);
            current.source = uint4(winner.candidate.point.recordIndex, 0u, uint(LightKind.Emitter), 0u);
        }

        // An imported sample needs the pair (p-hat, pdf) of this receiver before the merge can rescale its weight, and
        // the same pair to shade with afterwards, so one evaluation serves both.
        BlockEmitterEstimate imported = blockEmitterEmptyEstimate();
        bool hasImported = false;
        if (historyValid != 0u) {
            float3 previousReceiver;
            float previousDistance;
            int2 historyPixel;
            bool onScreen = diReprojectReceiver(receiver.position, cameraDelta, previousViewProjection, size,
                flipY != 0u, previousReceiver, previousDistance, historyPixel);
            DIReservoir history = emptyReservoir();
            if (onScreen && diLoadHistory(reservoirs, historyPixel, size, parity, previousReceiver, previousDistance,
                    normalize(receiver.normal), cameraStatic != 0u, cameraDelta, history)) {
                LightSample historySample = reservoirLightSample(history, receiver.position);
                LightEvaluation evaluation = emitters().evaluate(receiver, historySample);
                if (evaluation.valid && evaluation.targetPdf > 0.0 && evaluation.sourcePdf > 0.0) {
                    current = mergeReservoirs(current, history, evaluation.targetPdf,
                        RESTIR_DI_TEMPORAL_RATIO_CAP, RESTIR_DI_M_CAP, seed);
                    // Age even when the roulette kept the batch winner: the lane counts the frames this pixel's
                    // reservoir has survived, which is what a stale-history debug read needs.
                    current.source.y = min(current.source.y + 1u, 0xFFFFu);
                    imported.position = historySample.position;
                    imported.radiancePerEmission = evaluation.radiance;
                    imported.phat = evaluation.targetPdf;
                    imported.solidAnglePdf = evaluation.sourcePdf;
                    imported.endBias = blockEmitterStoredEndBias(records, historySample.lightIndex,
                        historySample.distance);
                    imported.recordIndex = historySample.lightIndex;
                    hasImported = true;
                }
            }
        }

        // The receiver lanes say which surface this record belongs to, and a merge never touches them: they describe the
        // receiver, and the receiver of a merged record is always the pixel that is writing it.  The self-emissive bit is
        // the one lane a *reader* needs that the shading of that reader cannot recover: passes running after this one
        // (spatial reuse) re-evaluate the sample without the surface's material at hand.
        float3 normal = normalize(receiver.normal);
        current.stats.w = (normal.z < 0.0 ? DI_RECEIVER_NORMAL_DOWN : 0u)
            | (receiver.emission >= 0.5 ? DI_RECEIVER_SELF_EMISSIVE : 0u);
        current.receiver = float4(receiver.position, reservoirPackNormalXY(normal.xy));
        reservoirs[reservoirSlot()] = current;

        if (!(current.stats.x > 0.0) || !(current.stats.y > 0.0)) {
            return result;   // nothing won: the record just published is empty and the next frame starts over
        }
        BlockEmitterEstimate estimate;
        if (winner.hasWinner && diSamePosition(current.sample.xyz, winner.candidate.point.position)) {
            estimate = blockEmitterEstimateFromWinner(winner, atlas);
        } else if (hasImported) {
            estimate = imported;
        } else {
            return result;
        }
        // The weight of the merged reservoir, not of the batch alone: reusing a history is reusing its accumulated sum.
        return blockEmitterEstimateSample(estimate, current.stats.y, current.stats.x, receiver);
    }

    // The legacy occlusion feedback: a reservoir behind a new occluder keeps its sample and decays its weight, so it
    // fades out over a few frames instead of glowing through the wall or switching off in one.
    void reuseConfirm(bool occluded) {
        if (!occluded || !reuseActive()) {
            return;
        }
        uint ownSlot = reservoirSlot();
        DIReservoir record = reservoirs[ownSlot];
        record.stats.y *= RESTIR_DI_OCCLUSION_DECAY;
        reservoirs[ownSlot] = record;
    }

    // A pixel whose primary ray never accepted a surface has no reservoir of its own; publishing an empty record keeps a
    // stale history from resurrecting a light that has left the screen.
    void reuseClear() {
        if (!reuseActive()) {
            return;
        }
        reservoirs[reservoirSlot()] = emptyReservoir();
    }
};
