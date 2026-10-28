// Block-emitter next-event estimation for the Slang path tracer.
//
// The record is the one RtEmitters already uploads for the legacy rt_path.comp: six vec4 per emitter, positions relative to
// the block origin the list was rebuilt at. The host therefore binds the same buffer to both paths; the camera-relative
// correction the legacy shader reads from StatsBuf arrives as a push constant here.
//
// The transport kernel is the legacy one, not the physical inverse square: a d^-2 tail left a torch lighting a four-block
// puddle while the rest of the room stayed black (owner report 2026-10-23), so the falloff is d^-1.5 up to two blocks and
// d^-1.25 beyond, windowed at EMITTER_RADIUS. That kernel, and the RIS weight of the whole candidate batch, is folded into
// `radiance` because the legacy p-hat carries the receiver cosine inside the target density while bsdfEvaluate multiplies
// the cosine again; `sourcePdf` stays the honest solid-angle density of the chosen point so ReSTIR can reuse the sample.
//
// Entity-atlas emitters (uvc.w != 0) are skipped: the entity texture is not bound to this pass yet, and a candidate that
// cannot be evaluated must not take part in the weight sum.

public module block_emitters;

import "contracts/light.slang";
import "contracts/math.slang";
import "light/sun.slang";
import "sampling.slang";
import "surface/surface.slang";

static const float EMITTER_RADIUS = 24.0;
static const float EMISSIVE_GAIN = 3.0;
static const float EMISSIVE_FLUX_GAIN = 2.2;
static const uint EMITTER_RECORD_FLOATS = 24u;

struct BlockEmitterRecord {
    float4 corner;    // xyz corner of the emissive quad, w area in blocks^2
    float4 edge1;     // xyz p1 - p0, w power boost of a small source
    float4 edge2;     // xyz p3 - p0, w 0.5 = two-sided / open source, 1.5 = redstone
    float4 uvCorner;  // xy uv(p0), z mean texel emission, w entity atlas slot + 1 (0 = block atlas)
    float4 uvEdges;   // xy uv(p1) - uv(p0), zw uv(p3) - uv(p0)
    float4 tint;      // entity emitter tint multiplier; block emitters are white
};

// The number of records the host bound.  The emitter list is a descriptor range of exactly count * 96 bytes, so this is
// the count and a stale quad behind a shortened selection can never be drawn.  Slang's GetDimensions also reports the
// stride, which this module never needs.
uint blockEmitterCount(StructuredBuffer<BlockEmitterRecord> records) {
    uint count;
    uint stride;
    records.GetDimensions(count, stride);
    return count;
}

// Falloff of a source: d^-1.5 up to 2 blocks, then d^-1.25, continuous at the 2-block seam (0.84090 = 2^1.25 / (2*sqrt(2))).
// Written with rsqrt instead of pow: eight of these per NEE call sit in the hottest loop of the emissive pass.
float emitterAttenuation(float d2) {
    float dd = max(d2, 0.25);
    return dd <= 4.0 ? rsqrt(dd * sqrt(dd)) : 0.84090 * rsqrt(dd * sqrt(sqrt(dd)));
}

// The game's own block-light window: a source reaches EMITTER_RADIUS blocks, not the whole ray distance, or a lava column
// ten blocks away painted the walls orange in daylight.
float emitterWindow(float d2, float lightRadius) {
    float window = clamp(1.0 - d2 / (lightRadius * lightRadius), 0.0, 1.0);
    return window * window;
}

float3 emitterAttenuationColor(float3 tex) {
    float m = max(tex.r, max(tex.g, tex.b));
    if (m <= 1.0e-4) {
        return float3(0.0);
    }
    float3 c = tex / m;

    // Category 1: pure redstone (torch, wire, lamp, repeater, ore).
    if (c.r > 0.65 && c.g < 0.22 && c.b < 0.22) {
        return float3(1.0, 0.05, 0.02) * lerp(m, 1.0, 0.35);
    }
    // Category 2: warm flames and firelight (torch, campfire, lantern, lava, candle).
    if (c.r >= 0.55 && c.r > c.b * 1.25 && c.g >= 0.18) {
        float warm = clamp((c.g - 0.20) / 0.60, 0.0, 1.0);
        float3 flame = lerp(float3(1.0, 0.52, 0.10), float3(1.0, 0.76, 0.26), warm);
        return flame * 1.35 * lerp(m, 1.0, 0.45);
    }
    // Category 3: soul fire.
    if (c.b > 0.45 && c.g > 0.35 && c.r < 0.45) {
        return float3(0.18, 0.85, 1.0) * 1.2 * lerp(m, 1.0, 0.4);
    }
    // Category 4: sea lantern and conduit.
    if (c.b > 0.65 && c.g > 0.65 && c.r > 0.35 && c.b > c.r * 1.05) {
        return float3(0.75, 0.94, 1.0) * 1.15 * lerp(m, 1.0, 0.4);
    }
    // Category 5: glowstone, shroomlight, ochre froglight.
    if (c.r > 0.7 && c.g > 0.65 && c.b < 0.45) {
        return float3(1.0, 0.85, 0.42) * 1.2 * lerp(m, 1.0, 0.4);
    }
    // Category 6: amethyst, crying obsidian, nether portal.
    if (c.r > 0.45 && c.b > 0.55 && c.g < 0.45) {
        return float3(0.85, 0.30, 1.0) * 1.2 * lerp(m, 1.0, 0.4);
    }
    // Category 7: end rod and purpur.
    if (c.r > 0.6 && c.b > 0.75 && c.g >= 0.45) {
        return float3(0.92, 0.85, 1.0) * 1.25 * lerp(m, 1.0, 0.4);
    }
    // Category 8: bioluminescence (glow lichen, cave vines, glow berries).
    if (c.g > 0.65 && c.b > 0.35 && c.r < 0.5) {
        return float3(0.45, 1.0, 0.75) * 1.15 * lerp(m, 1.0, 0.4);
    }
    // Default: keep the chromaticity without desaturating it hard.
    return lerp(c, float3(1.0), 0.08) * lerp(m, 1.0, 0.5);
}

// The geometry of one candidate sample point, in the frame of the receiver that asked for it.  `position` is world
// (bob-included) space; a screen-space reservoir stores it camera-relative instead, or a view bob moves every stored
// sample by a fraction of a block each frame and the temporal reuse never fires while walking.
struct BlockEmitterPoint {
    float3 position;       // sampled point on the emissive rectangle, world space
    float2 uv;             // atlas texel of that point
    float3 emitterNormal;  // cross(e1, e2) of the quad, the legacy face normal
    float3 quadCorner;     // world corner p0 of the quad
    float3 edge1;
    float3 edge2;
    uint   recordIndex;
    float  texelEmission;
    float  area;
    float  indexProbability;
    bool   twoSided;
    bool   redstone;
};

// Unshadowed target density p^ of one candidate at one receiver: the legacy kernel (emission * the two cosines * the
// windowed d^-1.5 / d^-1.25 falloff).  Shared by the candidate batch and by the re-evaluation of a stored sample, so a
// reservoir import and a fresh sample can never disagree about what p^ means.
float blockEmitterPhat(BlockEmitterPoint p, LightReceiver receiver, float distance2, float lightRadius) {
    float3 toReceiver = p.position - receiver.position;
    float3 direction = toReceiver * rsqrt(distance2);
    float cosReceiver = max(dot(receiver.normal, direction), 0.0);
    if (cosReceiver <= 0.0) {
        return 0.0;
    }
    float cosEmitter = dot(p.emitterNormal, -direction);
    // The density keeps the true projection; the look kernel floors the cosine of an open source so an edge-on torch does
    // not switch off, and a one-sided face is rejected outright.
    float cosGeometry = p.twoSided ? abs(cosEmitter) : max(cosEmitter, 0.0);
    float cosKernel = p.twoSided ? max(abs(cosEmitter), 0.25) : max(cosEmitter, 0.0);
    if (cosGeometry <= 1.0e-5 || cosKernel <= 0.0) {
        return 0.0;
    }
    float window = emitterWindow(distance2, lightRadius);
    return p.texelEmission * cosKernel * cosReceiver * window * emitterAttenuation(distance2);
}

// Geometric cosine of a candidate, the term the density divides back out of the estimator.
float blockEmitterCosGeometry(BlockEmitterPoint p, LightReceiver receiver, float distance2) {
    float3 direction = (p.position - receiver.position) * rsqrt(distance2);
    float cosEmitter = dot(p.emitterNormal, -direction);
    return p.twoSided ? abs(cosEmitter) : max(cosEmitter, 0.0);
}

// Solid-angle density of the chosen point: the quad is sampled by area, so the Jacobian to the direction is d^2 / cos.
float blockEmitterSolidAnglePdf(BlockEmitterPoint p, float distance2, float cosGeometry) {
    float probability = max(p.indexProbability, 1.0e-8);
    float area = max(p.area, 1.0e-6);
    return (probability / area) * distance2 / max(cosGeometry, 1.0e-5);
}

// Radiance the chosen point carries, without visibility and without the RIS weight: the texture colour of the emissive
// texel times the legacy gains.  A reservoir stores exactly this and divides `texelEmission` back out when it shades.
float3 blockEmitterRadiance(BlockEmitterPoint p, MaterialAtlas atlas) {
    if (p.redstone) {
        return float3(1.0, 0.04, 0.02) * (EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN);
    }
    float3 tex = atlas.albedo.SampleLevel(p.uv, 0.0).rgb;
    return emitterAttenuationColor(tex) * p.texelEmission * (EMISSIVE_GAIN * EMISSIVE_FLUX_GAIN);
}

// Where the shadow ray stops short of the light.  An open source (a flame on its stick, a bulb in its cage) is sampled on
// faces that may look away from the receiver: without this the ray crosses the body of the source and the stick shadows
// its own light.
float blockEmitterEndBias(BlockEmitterPoint p, float distanceTo) {
    float endBias = clamp(0.02 * distanceTo, 0.002, 0.03);
    return p.twoSided ? max(endBias, min(0.2, 0.45 * distanceTo)) : endBias;
}

// One evaluated candidate of the batch, with the two quantities the estimator needs besides p^.
struct BlockEmitterCandidate {
    BlockEmitterPoint point;
    float             distance2;
    float             phat;
    float             cosGeometry;
};

// A candidate that carries no weight: every field zeroed, so a rejected draw leaves the batch sum and the roulette
// untouched and a caller can test `phat > 0` instead of a separate validity flag.
BlockEmitterPoint blockEmitterEmptyPoint(uint index) {
    BlockEmitterPoint point;
    point.position = float3(0.0);
    point.uv = float2(0.0);
    point.emitterNormal = float3(0.0, 1.0, 0.0);
    point.quadCorner = float3(0.0);
    point.edge1 = float3(0.0);
    point.edge2 = float3(0.0);
    point.recordIndex = index;
    point.texelEmission = 0.0;
    point.area = 0.0;
    point.indexProbability = 0.0;
    point.twoSided = false;
    point.redstone = false;
    return point;
}

BlockEmitterCandidate blockEmitterEmptyCandidate(uint index) {
    BlockEmitterCandidate candidate;
    candidate.point = blockEmitterEmptyPoint(index);
    candidate.distance2 = 0.0;
    candidate.phat = 0.0;
    candidate.cosGeometry = 0.0;
    return candidate;
}

// The quad geometry of a record, without a sampled point: a reservoir stores an index and a position, and the position
// alone does not say whether the face it sits on is one-sided or an open source.
BlockEmitterPoint blockEmitterPointFromRecord(BlockEmitterRecord emitter, uint index) {
    BlockEmitterPoint point = blockEmitterEmptyPoint(index);
    point.area = max(emitter.corner.w, 1.0e-6);
    point.twoSided = emitter.edge2.w > 0.5;
    return point;
}

// The same bias for a position that came back from a reservoir: the record stores the emitter index, not the point's
// private fields, so the two-sided flag is read from the list it was drawn from.
float blockEmitterStoredEndBias(StructuredBuffer<BlockEmitterRecord> records, uint index, float distanceTo) {
    if (index >= blockEmitterCount(records)) {
        return clamp(0.02 * distanceTo, 0.002, 0.03);
    }
    return blockEmitterEndBias(blockEmitterPointFromRecord(records[index], index), distanceTo);
}


bool blockEmitterCandidateValid(BlockEmitterCandidate candidate) {
    return candidate.phat > 0.0;
}

// The list is sorted by importance (RtEmitters): index = n * u^2 favours its front, and the probability of the slot is the
// difference of the sqrt ladder it sits on.
uint blockEmitterPickIndex(uint count, inout uint seed) {
    float u = rand01(seed);
    return min(uint(u * u * float(count)), count - 1u);
}

float blockEmitterIndexProbability(uint index, uint count) {
    return (sqrt(float(index + 1u)) - sqrt(float(index))) * rsqrt(float(count));
}

// The whole candidate batch of one receiver: K stratified points on K importance-sorted quads, resampled to one winner.
// Shared by the plain RIS sampler and by the ReSTIR DI initial batch, so both draw the same random stream and weight the
// same p^.
struct BlockEmitterWinner {
    BlockEmitterCandidate candidate;
    float                 weightSum;
    float                 sampleCount;   // draws made: the M a temporal merge adds to
    bool                  hasWinner;
};

// One stratified point of one record, evaluated for one receiver.  A point that cannot carry weight is rejected before the
// atlas fetches: outside the light radius, inside the emitter's own body (the self-rejection of a glowing face), or a
// texel that does not glow.
BlockEmitterCandidate blockEmitterCandidate(StructuredBuffer<BlockEmitterRecord> records, MaterialAtlas atlas,
        uint index, float2 quad, LightReceiver receiver, float3 cameraRelative, float lightRadius) {
    BlockEmitterCandidate candidate = blockEmitterEmptyCandidate(index);
    BlockEmitterPoint point = candidate.point;
    if (index >= blockEmitterCount(records)) {
        return candidate;
    }

    BlockEmitterRecord emitter = records[index];
    if (uint(emitter.uvCorner.w + 0.5) != 0u) {
        return candidate;   // entity-atlas emitter: not evaluable in this pass
    }
    point.position = emitter.corner.xyz - cameraRelative
        + emitter.edge1.xyz * quad.x + emitter.edge2.xyz * quad.y;
    point.uv = emitter.uvCorner.xy + emitter.uvEdges.xy * quad.x + emitter.uvEdges.zw * quad.y;
    point.quadCorner = emitter.corner.xyz - cameraRelative;
    point.edge1 = emitter.edge1.xyz;
    point.edge2 = emitter.edge2.xyz;
    point.area = max(emitter.corner.w, 1.0e-6);
    point.twoSided = emitter.edge2.w > 0.5;
    point.indexProbability = blockEmitterIndexProbability(index, blockEmitterCount(records));
    candidate.point = point;

    float3 toPoint = point.position - receiver.position;
    float distance2 = dot(toPoint, toPoint);
    // Both zero tests of the legacy p-hat hoisted in front of the atlas fetch: such a candidate carries no weight.
    // The second one is the self-rejection: a face that is itself a light is not lit by its own quad.
    if (distance2 > lightRadius * lightRadius
        || distance2 < (receiver.emission > 0.5 ? 0.25 : 1.0e-6)) {
        return candidate;
    }
    candidate.distance2 = distance2;

    float4 materialTexel = atlas.material.SampleLevel(point.uv, 0.0);
    point.redstone = emitter.edge2.w > 1.5 && ((255u - uint(materialTexel.a * 255.0 + 0.5)) & 4u) != 0u;
    point.texelEmission = point.redstone ? emitter.uvCorner.z : materialTexel.b * emitter.edge1.w;
    candidate.point = point;
    if (point.texelEmission <= 0.0) {
        return candidate;
    }
    float3 crossEdge = cross(emitter.edge1.xyz, emitter.edge2.xyz);
    float crossLength = length(crossEdge);
    point.emitterNormal = crossLength > 1.0e-6 ? crossEdge / crossLength : float3(0.0, 1.0, 0.0);
    candidate.point = point;

    candidate.phat = blockEmitterPhat(point, receiver, distance2, lightRadius);
    if (candidate.phat > 0.0) {
        candidate.cosGeometry = blockEmitterCosGeometry(point, receiver, distance2);
    }
    return candidate;
}

BlockEmitterWinner sampleBlockEmitterBatch(StructuredBuffer<BlockEmitterRecord> records, MaterialAtlas atlas,
        LightReceiver receiver, float3 cameraRelative, float maxDistance, uint candidates, inout uint seed) {
    BlockEmitterWinner winner;
    winner.candidate = blockEmitterEmptyCandidate(0u);
    winner.weightSum = 0.0;
    winner.sampleCount = 0.0;
    winner.hasWinner = false;
    if (blockEmitterCount(records) == 0u || candidates == 0u) {
        return winner;
    }
    float lightRadius = min(maxDistance, EMITTER_RADIUS);
    // One per-call shift of the R2 sequence stratifies the quads of one torch evenly instead of by independent points.
    float2 r2shift = float2(rand01(seed), rand01(seed));
    uint count = blockEmitterCount(records);
    for (uint k = 0u; k < candidates; ++k) {
        uint index = blockEmitterPickIndex(count, seed);
        float2 quad = frac(r2shift + float(k) * float2(0.7548776662, 0.5698402909));
        BlockEmitterCandidate candidate = blockEmitterCandidate(records, atlas, index, quad, receiver,
            cameraRelative, lightRadius);
        winner.sampleCount += 1.0;
        if (!blockEmitterCandidateValid(candidate)) {
            continue;
        }
        float weight = candidate.phat * candidate.point.area / max(candidate.point.indexProbability, 1.0e-8);
        winner.weightSum += weight;
        if (weight > 0.0 && rand01(seed) * winner.weightSum < weight) {
            winner.candidate = candidate;
            winner.hasWinner = true;
        }
    }
    return winner;
}

// The chosen point of one winner, reduced to what the estimator multiplies.  A fresh candidate and a reservoir import
// fill the same five quantities, so the pair (radiance, sourcePdf) a path vertex receives cannot depend on which of the
// two produced it.  `radiancePerEmission` is Le without the texel emission: the legacy estimator divides the emission
// back out of the p-hat it folded it into, and both sides do it here once.
struct BlockEmitterEstimate {
    float3 position;             // the point on the emissive quad
    float3 radiancePerEmission;  // Le / texelEmission
    float  phat;                 // target density at this receiver
    float  solidAnglePdf;
    float  endBias;
    uint   recordIndex;
};

// Legacy NEE returns albedo/PI * Le * transmittance * (W/K), with the receiver cosine already inside W. The BSDF
// evaluation of this path multiplies the cosine again, so it comes back out here and the pair below reproduces the
// legacy integrand exactly for K = 1 and its RIS average for K > 1.
LightSample blockEmitterEstimateSample(BlockEmitterEstimate e, float weightSum, float sampleCount,
        LightReceiver receiver) {
    LightSample result = invalidLightSample();
    if (e.solidAnglePdf <= 0.0 || e.phat <= 0.0) {
        return result;
    }
    float3 direction = e.position - receiver.position;
    float distanceTo = length(direction);
    direction = safeNormalize(direction, float3(0.0, 1.0, 0.0));
    float cosReceiver = max(dot(receiver.normal, direction), 0.0);
    if (cosReceiver <= 0.0) {
        return result;
    }
    float risWeight = (weightSum / max(sampleCount, 1.0)) / max(cosReceiver, 1.0e-4);
    float shadowDistance = max(distanceTo - e.endBias, 0.001);
    result.valid = true;
    result.kind = LightKind.Emitter;
    result.direction = direction;
    // The shadow ray of the path tracer ends where the legacy one did: short of the emitter body, not inside it.
    result.position = receiver.position + direction * shadowDistance;
    result.distance = shadowDistance;
    result.radiance = e.radiancePerEmission * risWeight * e.solidAnglePdf;
    result.targetPdf = e.phat;
    result.sourcePdf = e.solidAnglePdf;
    result.lightIndex = e.recordIndex;
    return result;
}

// An estimate that carries nothing: every field zeroed, so a caller can hold one before it knows which branch filled it.
BlockEmitterEstimate blockEmitterEmptyEstimate() {
    BlockEmitterEstimate estimate;
    estimate.position = float3(0.0);
    estimate.radiancePerEmission = float3(0.0);
    estimate.phat = 0.0;
    estimate.solidAnglePdf = 0.0;
    estimate.endBias = 0.0;
    estimate.recordIndex = 0u;
    return estimate;
}

// The estimator quantities of a fresh batch winner.  Shared by the plain RIS sampler and by the ReSTIR DI initial batch,
// so the two cannot disagree about what the same draw means.
BlockEmitterEstimate blockEmitterEstimateFromWinner(BlockEmitterWinner winner, MaterialAtlas atlas) {
    BlockEmitterPoint chosen = winner.candidate.point;
    BlockEmitterEstimate estimate;
    estimate.position = chosen.position;
    // `blockEmitterRadiance` carries texelEmission, the estimator divides it back out: the pair is the legacy Le.
    estimate.radiancePerEmission = blockEmitterRadiance(chosen, atlas) / max(chosen.texelEmission, 1.0e-4);
    estimate.phat = winner.candidate.phat;
    estimate.solidAnglePdf = blockEmitterSolidAnglePdf(chosen, winner.candidate.distance2,
        winner.candidate.cosGeometry);
    estimate.endBias = blockEmitterEndBias(chosen, sqrt(winner.candidate.distance2));
    estimate.recordIndex = chosen.recordIndex;
    return estimate;
}

// Resampled importance sampling over the emitter list.  The host binds the list with a range of exactly count records, so
// `blockEmitterCount` is the count: a stale quad behind a shortened selection can never be drawn.
struct BlockEmitterSampler : ILightSampler {
    StructuredBuffer<BlockEmitterRecord> records;
    MaterialAtlas atlas;
    uint candidates;
    float3 cameraRelative;    // camera position minus the origin the list was rebuilt at
    float maxDistance;        // ray window of the caller; the light radius is min(maxDistance, EMITTER_RADIUS)

    LightSample sample(LightReceiver receiver, inout uint seed) {
        LightSample result = invalidLightSample();
        BlockEmitterWinner winner = sampleBlockEmitterBatch(records, atlas, receiver, cameraRelative,
            maxDistance, candidates, seed);
        if (!winner.hasWinner) {
            return result;
        }
        return blockEmitterEstimateSample(blockEmitterEstimateFromWinner(winner, atlas),
            winner.weightSum, winner.sampleCount, receiver);
    }

    // Re-evaluation of one stored sample at another receiver: the position is projected back onto the quad for its texel and
    // the shared candidate path evaluates it, so a reused sample and a fresh one can never disagree about p^ or Le.
    LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {
        LightEvaluation result = invalidLightEvaluation();
        if (sample.kind != LightKind.Emitter) {
            return result;
        }
        float lightRadius = min(maxDistance, EMITTER_RADIUS);
        uint count = blockEmitterCount(records);
        if (count == 0u) {
            return result;
        }
        uint index = sample.lightIndex >= count ? sample.lightIndex % count : sample.lightIndex;
        BlockEmitterRecord emitter = records[index];
        float3 local = emitter.corner.xyz - cameraRelative;
        // The quads of RtEmitters are axis-aligned, so the two edges are orthogonal and the projection is exact.
        float3 offset = sample.position - local;
        float u = dot(offset, emitter.edge1.xyz) / max(dot(emitter.edge1.xyz, emitter.edge1.xyz), 1.0e-8);
        float v = dot(offset, emitter.edge2.xyz) / max(dot(emitter.edge2.xyz, emitter.edge2.xyz), 1.0e-8);
        if (u < 0.0 || u > 1.0 || v < 0.0 || v > 1.0) {
            return result;   // the stored point is not on this quad: the list changed under the reservoir
        }
        BlockEmitterCandidate candidate = blockEmitterCandidate(records, atlas, index, float2(u, v),
            receiver, cameraRelative, lightRadius);
        if (!blockEmitterCandidateValid(candidate)) {
            return result;
        }
        float solidAnglePdf = blockEmitterSolidAnglePdf(candidate.point, candidate.distance2, candidate.cosGeometry);
        if (solidAnglePdf <= 0.0) {
            return result;
        }
        result.valid = true;
        result.radiance = blockEmitterRadiance(candidate.point, atlas) / max(candidate.point.texelEmission, 1.0e-4);
        result.targetPdf = candidate.phat;
        result.sourcePdf = solidAnglePdf;
        return result;
    }
};

// One branch of the key light, one of the emitters: the selection probability belongs to the composite because the
// estimator has to divide by the density of the branch that produced the sample.
struct SunAndBlockEmitterSampler : ILightSampler {
    SunMoonSampler keyLight;
    BlockEmitterSampler emitters;
    float emitterProbability;
    // Set when the caller lights the primary vertex from a ReSTIR reservoir: that branch is evaluated every frame (the
    // record has to be read and written whether or not it wins a draw), so the composite must not also draw emitters for
    // it.  Path vertices deeper in the path keep the plain RIS branch.
    bool emittersReusedAtPrimary;

    LightSample sample(LightReceiver receiver, inout uint seed) {
        bool hasEmitters = blockEmitterCount(emitters.records) > 0u && emitters.candidates > 0u
            && emitterProbability > 0.0 && !(receiver.primary && emittersReusedAtPrimary);
        float sunIntensity = luminance(keyLight.sun.color.rgb) * max(keyLight.sun.color.w, 0.0)
            + luminance(keyLight.sun.moonColor.rgb) * max(keyLight.sun.moonColor.w, 0.0);
        if (!hasEmitters || sunIntensity <= 0.0) {
            // One branch only: its density is already the whole density, so nothing to renormalise.
            if (hasEmitters && sunIntensity <= 0.0) {
                return emitters.sample(receiver, seed);
            }
            return keyLight.sample(receiver, seed);
        }
        if (rand01(seed) < emitterProbability) {
            LightSample emitterSample = emitters.sample(receiver, seed);
            if (emitterSample.valid) {
                emitterSample.sourcePdf *= emitterProbability;
            }
            return emitterSample;
        }
        LightSample sunSample = keyLight.sample(receiver, seed);
        if (sunSample.valid) {
            sunSample.sourcePdf *= 1.0 - emitterProbability;
        }
        return sunSample;
    }

    LightEvaluation evaluate(LightReceiver receiver, LightSample sample) {
        if (sample.kind == LightKind.Emitter) {
            return emitters.evaluate(receiver, sample);
        }
        return keyLight.evaluate(receiver, sample);
    }
};
