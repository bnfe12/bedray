// Path-space ReSTIR for Bedray 2.0 — reservoir math only.  v3.
//
// v3 changes (change of measure for the fixed-first-vertex shift):
//   * reuse is now restricted to one certified class of paths: a diffuse first
//     vertex whose suffix radiance and sampling distribution do not depend on
//     the incoming edge.  Sky and emitter first edges, delta and glossy first
//     vertices are rejected (v2 admitted rough GGX and sky paths, whose
//     radiance is direction-dependent, so no density ratio could correct them).
//   * the shift correction is derived and implemented: the stored first-edge
//     density transports as q_new = q_old * G_old / G_new where
//     G(anchor) = |cos at the first vertex| / r^2, so the merge multiplies the
//     target ratio by J = G_new / G_old (ptFirstEdgeShiftJacobian).  The
//     derivation and its CPU validation are in docs/RESTIR_GI_DERIVATION.md.
//   * the firefly/grazing guards (ratio caps, minimum edge cosine) are
//     estimator changes, not corrections: they bias the estimate towards
//     history and the module documents them as such.
//   * each first vertex carries a stable `sampleId`; the duplicate test is an
//     identity comparison, not a position heuristic (which dropped distinct
//     samples that shared a vertex).
//
// v2 changes (alpha 0.2.0 architecture review):
//   * the reservoir shrank from 304 B to 112 B per pixel: the estimator only
//     ever read the anchor and the first scattered vertex, so the 4-vertex
//     array is gone.  The memory formula is in the layout comment below.
//   * sky (ENVIRONMENT) paths lost their visibility exemption: a reused sky
//     edge is traced like any other first edge, in both resolves and in the
//     spatial acceptance test.  A fresh sky path still needs no ray.
//   * DI/PT first-edge overlap got an ownership policy.  The default is
//     DI_OWNER (no double count); SUM remains available for a caller that
//     runs PT without DI.  The indirect part of a path is never touched by
//     the policy — only the first-edge emitter-terminated term overlaps DI.
//   * the emitter decision is a material-decode contract (ptTerminalEmits),
//     never HIT_FLAG_EMISSIVE: the hardware backend does not set that flag.
//
// Path convention (a RestirPtPath is usable only while ptPathValid is true):
//
//   anchor        receiver-side vertex: the visible surface that owns the
//                 path.  Stored as position + normal only; the reservoir's
//                 estimator never shades it.
//   first         the first scattered vertex.  `pdf` is the solid-angle
//                 density used to sample edge 0 -> 1; `bsdfPdf` is the
//                 reverse BSDF density at that vertex for the direction
//                 1 -> 0; `throughput` is the suffix radiance (below);
//                 `flags` carries the first-edge statements.
//
// What `throughput` must and must not contain: the radiance the suffix
// delivers to the receiver, without the receiver BSDF and without a division
// by the sampling pdf.  The caller shades once at resolve time:
//
//     bsdfEvaluate(receiverMaterial, view, receiverNormal, direction)
//         * radiance * weight
//
// A reused path must be shaded with the BSDF of the surface being rendered
// now, not the one that sampled it.  The first-edge pdf lives in `pdf`, so a
// fresh path resolves to the ordinary Monte Carlo estimate f*cos*L/p_source.
//
// Coordinate space: vertex positions are camera-relative
// (contracts/hit.slang).  A path written by an earlier frame is expressed in
// that frame's camera-relative space and must be rebased before its edges can
// be compared with the current receiver (ptTemporalReuse does it with
// ptHistoryOriginDelta; skipping the rebase is only correct while the camera
// does not move).
//
// Reuse never adds radiance: ptAddPath and both reuse steps move only M and
// the weight sum, and every resolve returns the weight once.

public module restir_pt;

import "contracts/material.slang";
import "contracts/hit.slang";
import "contracts/history.slang";
import "contracts/math.slang";
import "sampling.slang";
import "surface/surface.slang";
import "surface/visibility.slang";
import "traversal/traversal.slang";

// ---- RestirPtPath.flags -----------------------------------------------------
//
// The bits live at 24 and above, outside the SurfaceHit/HIT_FLAG namespace, so
// a caller may copy a hit flag word into a vertex without a cutout texel
// reading as "sky".  Only the first vertex may set ENVIRONMENT, EMISSIVE_END
// and DIFFUSE_FIRST: all three are statements about the first edge.
static const uint RESTIR_PT_PATH_FLAG_RECEIVER       = 1u << 24u;
static const uint RESTIR_PT_PATH_FLAG_ENVIRONMENT    = 1u << 25u;  // first edge escaped to the sky
static const uint RESTIR_PT_PATH_FLAG_DELTA          = 1u << 26u;  // contains a delta vertex; never reusable
static const uint RESTIR_PT_PATH_FLAG_EMISSIVE_END   = 1u << 28u;  // first edge ended on an emitter
// The generator certifies that the first scattered vertex is diffuse: its
// outgoing radiance and its lobe density do not depend on the incoming edge,
// which is the condition the fixed-first-vertex shift needs for the geometry
// ratio to be the whole change of measure.  Only a certified path is reusable;
// a rough, specular or delta first vertex stays local to its pixel.
static const uint RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST = 1u << 29u;

// RestirPtReservoir.source.y.  These live in their own field of the resolve
// result and must not be folded into RestirPtResolveResult.flags: the two
// namespaces overlap (OCCLUDED would read as ENVIRONMENT and skip the shadow
// ray), so a resolve reports them separately.
static const uint RESTIR_PT_RESERVOIR_FLAG_SAMPLED  = 1u << 0u;
static const uint RESTIR_PT_RESERVOIR_FLAG_REUSED   = 1u << 1u;
static const uint RESTIR_PT_RESERVOIR_FLAG_OCCLUDED = 1u << 2u;
static const uint RESTIR_PT_RESERVOIR_FLAG_CLAMPED  = 1u << 3u;  // candidate weight was capped
// Sticky: set when the occlusion decay changed the weight sum, cleared only
// when a new sample is selected.  W is no longer wSum/(M*p-hat) after a decay,
// so a record carrying this bit must never be treated as a fresh candidate.
static const uint RESTIR_PT_RESERVOIR_FLAG_DECAYED  = 1u << 4u;

// RestirPtResolveResult.flags
static const uint RESTIR_PT_RESOLVE_FLAG_VISIBILITY_TRACED = 1u << 0u;
static const uint RESTIR_PT_RESOLVE_FLAG_FILTER_OVERFLOW   = 1u << 1u;
static const uint RESTIR_PT_RESOLVE_FLAG_ENVIRONMENT       = 1u << 2u;
static const uint RESTIR_PT_RESOLVE_FLAG_WEIGHT_CLAMPED    = 1u << 3u;
static const uint RESTIR_PT_RESOLVE_FLAG_RADIANCE_CLAMPED  = 1u << 4u;

// RestirPtReservoir.source.z: how the stored path was produced.  Only the
// first technique exists today; the field lets a later light-tracing technique
// stay distinguishable without changing the record layout.  Light-traced
// paths have a different density structure and are NOT reusable in v2 — a
// merge that sees such a technique id rejects the record.
static const uint RESTIR_PT_TECHNIQUE_BSDF_PATH    = 0u;
static const uint RESTIR_PT_TECHNIQUE_LIGHT_TRACED = 1u;

static const float RESTIR_PT_MIN_PDF = 1.0e-8;
static const float RESTIR_PT_MIN_COSINE = 0.05;
static const float RESTIR_PT_MIN_DISTANCE = 1.0e-4;
// A first edge at a grazing angle to the first vertex's normal makes the
// solid-angle-to-area factor G = cos/r^2 approach zero, and the geometry ratio
// G_new/G_old explode.  Rejecting the candidate is a documented approximation
// (biased toward keeping the current sample), not part of the derivation.
static const float RESTIR_PT_MIN_EDGE_COSINE = 1.0e-3;
static const float RESTIR_PT_MAX_CANDIDATE_WEIGHT = 64.0;
static const float RESTIR_PT_MAX_RADIANCE = 1.0e6;
static const float RESTIR_PT_MAX_RESOLVED_WEIGHT = 1.0e4;

static const float RESTIR_PT_TEMPORAL_RATIO_CAP = 8.0;
static const float RESTIR_PT_SPATIAL_RATIO_CAP = 5.0;
static const float RESTIR_PT_M_CAP = 20.0;
static const float RESTIR_PT_MAX_M = 400.0;
static const float RESTIR_PT_OCCLUSION_DECAY = 0.25;

static const float RESTIR_PT_SPATIAL_MAX_DISTANCE = 1.0;
static const float RESTIR_PT_SPATIAL_MIN_NORMAL_DOT = 0.85;

// How far a resolve position may sit from a path's own anchor and still be
// considered the same receiver.  A receiver shift is at least one voxel, so a
// millimetre separates "same surface, recomputed position" from "resolved
// somewhere else".  Loosening it would run MIS on a density evaluated at
// another point; tightening it would reject valid history and fall back to a
// sum, which double counts the same term.
static const float RESTIR_PT_FRESH_ANCHOR_DISTANCE = 1.0e-3;

// ---- Record layout ----------------------------------------------------------
//
//   RestirPtFirstVertex  52 B  (position@0, normal@12, throughput@24,
//                               pdf@36, bsdfPdf@40, flags@44, sampleId@48)
//   RestirPtPath         80 B  (anchorPosition@0, anchorNormal@12,
//                               first@24, flags@76, no padding needed)
//   RestirPtReservoir   112 B  (sample@0, stats@80, source@96)
//
// Memory formula: bytes per pixel = 112; a temporal pass needs two buffers
// (current + history), so the budget is  112 * width * height * 2  bytes —
// 464.5 MB at 1080p, down from 2 * 630 MB in v1.  The fixed-size record keeps
// it a value type: no indirection, constant StructuredBuffer stride.  v3 grew
// the first vertex by 4 B (the sample id) but filled the path's tail padding,
// so neither the path nor the reservoir changed size.
//
// What was removed and why: vertices[2..3] were validated but never read by
// the estimator (the suffix is summarised in `first.throughput`), the vertex
// count is the constant 2, and `eta`/`depth` belonged only to the debug
// payload adapter, which v2 dropped.

struct RestirPtFirstVertex {
    float3 position;      // camera-relative
    float3 normal;        // shading normal at the vertex
    float3 throughput;    // suffix radiance delivered to the receiver
    float  pdf;           // solid-angle density of edge 0 -> 1 at the writer
    float  bsdfPdf;       // reverse BSDF density at this vertex for 1 -> 0
    uint   flags;         // first-edge statements (ENVIRONMENT, EMISSIVE_END)
    uint   sampleId;      // stable caller id of this sample; 0 = unknown
};

struct RestirPtPath {
    float3               anchorPosition;  // receiver anchor, camera-relative
    float3               anchorNormal;
    RestirPtFirstVertex  first;
    uint                 flags;           // RESTIR_PT_PATH_FLAG_*
};

// stats: x M (weighted samples), y weight sum, z target density of the selected
// path at the receiver that wrote the record, w confidence (M / M_CAP clamped).
// source: x frames since the stored path was selected (0 means this frame),
//         y RESTIR_PT_RESERVOIR_FLAG_*, z technique, w caller-defined stable
//         source identity of the light the suffix ends on — the emitter for
//         EMISSIVE_END paths, the sky-state identity for ENVIRONMENT paths
//         (0 = unknown/none).  The identity is only ever compared, never
//         weighted, so a caller can use a packed block position, an
//         emitter-list index or a revision counter.
struct RestirPtReservoir {
    RestirPtPath sample;
    float4       stats;
    uint4        source;
};

// Everything the module needs to know about the surface being shaded.
// `worldPosition` carries the world-anchored position historyValidate needs
// (SurfaceHit.worldPosition); `position` is camera-relative and is the one
// used for all distance and direction math.  v2 note: with the current
// backends `worldPosition` is a 1024-block-wrapped anchor, so it is a
// same-frame neighbourhood proxy, never a unique identity — see
// RESTIR_PT_NOTES.md §"worldPosition".
struct RestirPtReceiver {
    float3 position;
    float3 worldPosition;
    float3 normal;
    float  depth;         // positive view depth, used by historyValidate
    uint   materialId;
};

struct RestirPtResolveResult {
    bool   valid;
    float3 radiance;        // suffix radiance the stored path delivers
    float3 direction;       // receiver -> first path vertex
    float  distance;        // length of that first edge
    float  weight;          // wSum / (M * targetPdf): multiply once by the receiver lobe
    float  targetPdf;       // density used for the normalisation at this receiver
    float3 transmittance;   // coloured filter between receiver and first vertex
    uint   flags;           // RESTIR_PT_RESOLVE_FLAG_* only
    uint   reservoirFlags;  // mirror of source.y, RESTIR_PT_RESERVOIR_FLAG_*
    uint   emitterIdentity; // mirrors source.w of the resolved record
};

RestirPtFirstVertex ptEmptyFirstVertex() {
    RestirPtFirstVertex vertex;
    vertex.position = float3(0.0);
    vertex.normal = float3(0.0, 1.0, 0.0);
    vertex.throughput = float3(0.0);
    vertex.pdf = 0.0;
    vertex.bsdfPdf = 0.0;
    vertex.flags = 0u;
    vertex.sampleId = 0u;
    return vertex;
}

RestirPtPath ptEmptyPath() {
    RestirPtPath path;
    path.anchorPosition = float3(0.0);
    path.anchorNormal = float3(0.0, 1.0, 0.0);
    path.first = ptEmptyFirstVertex();
    path.flags = 0u;
    return path;
}

RestirPtReservoir ptEmptyReservoir() {
    RestirPtReservoir reservoir;
    reservoir.sample = ptEmptyPath();
    reservoir.stats = float4(0.0);
    reservoir.source = uint4(0u);
    return reservoir;
}

RestirPtResolveResult ptEmptyResolveResult() {
    RestirPtResolveResult result;
    result.valid = false;
    result.radiance = float3(0.0);
    result.direction = float3(0.0, 1.0, 0.0);
    result.distance = 0.0;
    result.weight = 0.0;
    result.targetPdf = 0.0;
    result.transmittance = float3(1.0);
    result.flags = 0u;
    result.reservoirFlags = 0u;
    result.emitterIdentity = 0u;
    return result;
}

// ---- Emitter decision (material-decode contract) ----------------------------
//
// HIT_FLAG_EMISSIVE is a backend hint, not a decision: voxel_traversal sets it,
// hardware_traversal never does (it writes only HIT_FLAG_FRONT_FACE).  The
// decision is made after decodeSurface, from the decoded material: with
// MATERIAL_SAMPLING_FLAG_EMISSIVE in effect decodeSurface has already zeroed
// the emission of anything the host did not mark, so "the terminal material
// emits" is exactly "any(emission > 0)".  The adapter that records the path
// calls this and passes the result to ptVertexFromHit; the module never reads
// the hit flag.
bool ptTerminalEmits(MaterialSample material) {
    return material.emission.r > 0.0 || material.emission.g > 0.0 ||
        material.emission.b > 0.0;
}

// ---- Validation -------------------------------------------------------------

// A path needs an anchor, a finite first vertex and a finite, non-negative
// suffix.  A history buffer is untrusted input, so a malformed field is
// rejected here instead of becoming a NaN in every merged neighbour.
// Negative radiance is rejected too: the target density uses |cos|, so nothing
// downstream would notice a wrong sign.
bool ptPathValid(RestirPtPath path) {
    uint terminalMask = RESTIR_PT_PATH_FLAG_ENVIRONMENT | RESTIR_PT_PATH_FLAG_EMISSIVE_END;
    uint terminal = path.first.flags & terminalMask;
    if ((path.flags & terminalMask) != terminal || terminal == terminalMask) return false;
    if (!isFinite(path.anchorPosition) || !isFinite(path.anchorNormal)) return false;
    if (dot(path.anchorNormal, path.anchorNormal) < 0.5) return false;
    if (!isFinite(path.first.position) || !isFinite(path.first.normal)) return false;
    if (!isFinite(path.first.throughput)) return false;
    if (path.first.throughput.r < 0.0 || path.first.throughput.g < 0.0 ||
        path.first.throughput.b < 0.0) return false;
    if (!isFinite(path.first.pdf) || path.first.pdf < 0.0) return false;
    if (!isFinite(path.first.bsdfPdf) || path.first.bsdfPdf < 0.0) return false;

    float3 firstEdge = path.first.position - path.anchorPosition;
    float distanceSquared = dot(firstEdge, firstEdge);
    return isFinite(distanceSquared) && distanceSquared > RESTIR_PT_MIN_DISTANCE * RESTIR_PT_MIN_DISTANCE;
}

// Reuse class check.  The derivation only covers a certified diffuse first
// vertex: its outgoing radiance and its lobe density are independent of the
// incoming edge, so the stored suffix is the same function of the vertex for
// every anchor that can see it.  A delta path has no finite density in this
// measure; a glossy or specular first vertex reflects the old edge's
// direction and does not transport; a sky or emitter first vertex carries
// radiance for exactly one incoming direction.  All of them are *rejected*,
// not approximated.  A zero source density would let a path the sampler never
// chose contribute and is rejected too.
//
// A black suffix is *not* rejected: the target density does not contain the
// radiance, so a black candidate still has a positive WRS weight and belongs
// in M and in the weight sum.
bool ptPathReusable(RestirPtPath path) {
    if (!ptPathValid(path)) return false;
    if ((path.flags & RESTIR_PT_PATH_FLAG_DELTA) != 0u) return false;
    if ((path.flags & RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST) == 0u) return false;
    uint terminalMask = RESTIR_PT_PATH_FLAG_ENVIRONMENT | RESTIR_PT_PATH_FLAG_EMISSIVE_END;
    if ((path.flags & terminalMask) != 0u) return false;
    return path.first.pdf > 0.0;
}

bool ptReservoirHasSample(RestirPtReservoir reservoir) {
    return isFinite(reservoir.stats.x) && isFinite(reservoir.stats.y) &&
           isFinite(reservoir.stats.z) &&
           reservoir.stats.x > 0.0 && reservoir.stats.y > 0.0 &&
           reservoir.stats.z > 0.0 && ptPathValid(reservoir.sample);
}

// Two records describe the same light source only when both identities are
// known and equal.  An unknown identity (0) never blocks a merge: it means the
// caller did not tag that record, not that the sources differ.  Callers should
// reserve 0 for "unknown" and offset their real ids by one, because an
// emitter-list index, a packed block origin or a revision counter can
// legitimately be 0.  v2: the same gate covers ENVIRONMENT paths, whose
// identity is the sky-state revision — a changed sun or sky is then an
// incompatible source, not a different emitter.
bool ptSourceCompatible(uint currentIdentity, uint incomingIdentity) {
    return currentIdentity == 0u || incomingIdentity == 0u ||
           currentIdentity == incomingIdentity;
}

// ---- Densities --------------------------------------------------------------

// Solid angle per unit area of the first edge at the first vertex, seen from
// `anchor`: G = |cos(normal, edge)| / r^2.  This is the factor that relates the
// solid-angle density of the first edge at one anchor to the same vertex
// sample seen from another; see docs/RESTIR_GI_DERIVATION.md.  A grazing edge
// or a coincident anchor returns 0, and the caller rejects the candidate.
float ptFirstEdgeGeometry(float3 firstPosition, float3 firstNormal, float3 anchor) {
    float3 toAnchor = anchor - firstPosition;
    float distanceSquared = dot(toAnchor, toAnchor);
    if (!isFinite(distanceSquared) ||
            distanceSquared <= RESTIR_PT_MIN_DISTANCE * RESTIR_PT_MIN_DISTANCE) {
        return 0.0;
    }
    float3 normal = safeNormalize(firstNormal, float3(0.0, 1.0, 0.0));
    float cosine = abs(dot(normal, toAnchor * rsqrt(distanceSquared)));
    if (cosine < RESTIR_PT_MIN_EDGE_COSINE) return 0.0;
    return cosine / distanceSquared;
}

// Change of measure for the fixed-first-vertex shift.  The stored first-edge
// density is q_old, measured at the anchor that sampled it; the same vertex
// sample viewed from `surfacePosition` has density q_new = q_old * G_old /
// G_new, so the merge multiplies the candidate's target ratio by
// J = q_old / q_new = G_new / G_old.  `originDelta` rebases the stored vertex
// into the current camera-relative space; pass 0 for a same-frame neighbour.
// Returns 0 when either endpoint is degenerate, and the caller rejects.
float ptFirstEdgeShiftJacobian(RestirPtFirstVertex first, float3 storedAnchor,
        float3 originDelta, float3 surfacePosition) {
    float oldGeometry = ptFirstEdgeGeometry(first.position, first.normal, storedAnchor);
    float newGeometry = ptFirstEdgeGeometry(first.position + originDelta, first.normal, surfacePosition);
    if (oldGeometry <= 0.0 || newGeometry <= 0.0) return 0.0;
    return newGeometry / oldGeometry;
}

// Sampling density of the stored path at its own receiver: the solid-angle
// density of the first edge.  The suffix density is shared by all candidates
// of a pixel, so it cancels between the target and the estimator and is not
// stored.
float ptPathSourcePdf(RestirPtPath path) {
    return max(path.first.pdf, RESTIR_PT_MIN_PDF);
}

// Target density of a path that starts at `surfacePosition` towards `vertex`:
// the reverse BSDF density recorded at the reused vertex times the receiver
// cosine.  `f_cur*cos` is not part of it on purpose — that is the caller's
// shading term, applied once at resolve; mixing the two would make the reuse
// ratio cancel instead of weight.
//
// The target cancels between the merge ratio and the resolve normalisation, so
// it is a variance control only; with the Jacobian in ptMergeReservoir the
// reused estimate is normalised by the transported density q_new.  The cosine
// is |cos| with a floor: a shared path is reused across the two sides of a
// leaf card, and a grazing receiver must not make the ratio explode. The floor
// is an approximation, not part of the change of measure.
float ptTargetPdf(RestirPtFirstVertex vertex, float3 surfacePosition,
        float3 surfaceNormal) {
    float3 toVertex = vertex.position - surfacePosition;
    float distanceSquared = dot(toVertex, toVertex);
    if (distanceSquared <= RESTIR_PT_MIN_DISTANCE * RESTIR_PT_MIN_DISTANCE) return 0.0;

    float3 direction = toVertex * rsqrt(distanceSquared);
    float3 normal = safeNormalize(surfaceNormal, float3(0.0, 1.0, 0.0));
    float cosine = clamp(abs(dot(normal, direction)), RESTIR_PT_MIN_COSINE, 1.0);
    return max(vertex.bsdfPdf, RESTIR_PT_MIN_PDF) * cosine;
}

float ptPathTargetPdf(RestirPtPath path, float3 surfacePosition,
        float3 surfaceNormal) {
    return ptTargetPdf(path.first, surfacePosition, surfaceNormal);
}

// ---- Helpers used by callers and by the reuse steps -------------------------

// Turns one traversal result into the first vertex.  `suffixRadiance` is the
// light the rest of the path delivers to the receiver: no receiver BSDF, no
// 1/pdf.  The ray is needed for the miss case, because an escaped path still
// has to record where on the environment the edge landed; otherwise every sky
// vertex would sit at the origin.
//
// `terminalEmits` comes from ptTerminalEmits on the material the caller just
// decoded — never from HIT_FLAG_EMISSIVE (hardware_traversal does not set it,
// and deriving the decision here would silently drop EMISSIVE_END on the
// hardware path and turn the DI-owner branch into a plain sum).
RestirPtFirstVertex ptVertexFromHit(Ray ray, SurfaceHit hit, float3 suffixRadiance,
        float sourcePdf, float reversePdf, bool terminalEmits) {
    RestirPtFirstVertex vertex = ptEmptyFirstVertex();
    vertex.position = hit.hit ? hit.position : rayAt(ray, hit.t);
    vertex.normal = hit.hit
        ? safeNormalize(hit.shadingNormal, hit.geometricNormal)
        : safeNormalize(-ray.direction, float3(0.0, 1.0, 0.0));
    vertex.throughput = suffixRadiance;
    vertex.pdf = max(sourcePdf, 0.0);
    vertex.bsdfPdf = max(reversePdf, 0.0);
    if (!hit.hit) {
        vertex.flags = RESTIR_PT_PATH_FLAG_ENVIRONMENT;
    } else if (terminalEmits) {
        vertex.flags = RESTIR_PT_PATH_FLAG_EMISSIVE_END;
    }
    return vertex;
}

// Records a fresh path: the receiver anchor plus the already-built first
// vertex.  The generator is responsible for the DIFFUSE_FIRST certificate (a
// diffuse first scattered vertex) and for DELTA on a delta vertex; both decide
// reusability, so they belong to the code that knows the lobe.
//
// `sampleId` is the caller's stable identity for this sample: unique among the
// samples a pixel can merge in one frame, and preserved by every copy of the
// record.  A tag of 0 means "unknown" and disables duplicate rejection, which
// is safe against double counting but wastes merges; the pass that eventually
// stamps it should fold the pixel index, the path index and the frame parity.
RestirPtPath ptRecordPath(float3 surfacePosition, float3 surfaceNormal,
        RestirPtFirstVertex first, bool diffuseFirst, bool delta,
        uint sampleId) {
    RestirPtPath path = ptEmptyPath();
    path.anchorPosition = surfacePosition;
    path.anchorNormal = safeNormalize(surfaceNormal, float3(0.0, 1.0, 0.0));
    path.first = first;
    path.first.sampleId = sampleId;
    // Terminal type is determined by the first hit and must survive in the
    // path-level flags used by resolve and DI/PT ownership policy.
    path.flags = RESTIR_PT_PATH_FLAG_RECEIVER |
        (first.flags & (RESTIR_PT_PATH_FLAG_ENVIRONMENT | RESTIR_PT_PATH_FLAG_EMISSIVE_END));
    if (diffuseFirst) path.flags |= RESTIR_PT_PATH_FLAG_DIFFUSE_FIRST;
    if (delta) path.flags |= RESTIR_PT_PATH_FLAG_DELTA;
    return path;
}

// Moves the receiver anchor of a stored path to the surface that now owns it.
// Only the anchor changes: the suffix and its recorded densities belong to the
// first scattered vertex and stay valid under the fixed-first-vertex shift.
RestirPtPath ptShiftPath(RestirPtPath path, float3 surfacePosition,
        float3 surfaceNormal) {
    path.anchorPosition = surfacePosition;
    path.anchorNormal = safeNormalize(surfaceNormal, float3(0.0, 1.0, 0.0));
    path.flags |= RESTIR_PT_PATH_FLAG_RECEIVER;
    return path;
}

// Translates the first vertex from the writing frame's camera-relative space
// into the current one, then moves the anchor.  The normal is a direction and
// a camera translation leaves it unchanged.
RestirPtPath ptRebasePath(RestirPtPath path, float3 originDelta,
        float3 surfacePosition, float3 surfaceNormal) {
    path.first.position += originDelta;
    return ptShiftPath(path, surfacePosition, surfaceNormal);
}

// Camera-origin delta between the frame that wrote a history buffer and this
// frame.  `header.prevCameraOrigin` is written by the host once per frame.
float3 ptHistoryOriginDelta(HistoryHeader header, float3 currentCameraOrigin) {
    return header.prevCameraOrigin.xyz - currentCameraOrigin;
}

// Standard spatial-reuse rejection: the two receivers must sit on the same
// surface in the same frame.  World-space proxy for the caller's screen-space
// test, not a replacement for it; first-edge occlusion is checked separately.
bool ptReuseCompatible(RestirPtReceiver receiver, RestirPtPath path,
        float maxDistance, float minNormalDot) {
    float3 delta = path.anchorPosition - receiver.position;
    if (dot(delta, delta) > maxDistance * maxDistance) return false;

    float3 normal = safeNormalize(receiver.normal, float3(0.0, 1.0, 0.0));
    float3 storedNormal = safeNormalize(path.anchorNormal, float3(0.0, 1.0, 0.0));
    return dot(normal, storedNormal) >= minNormalDot;
}

// Duplicate test: the same sample must not enter one reservoir twice, or its
// weight would be added twice.  v3 compares the caller's stable sample id
// instead of the first-vertex position: distinct samples can share a vertex
// (two suffix paths that hit the same surface point), and dropping one of them
// biases the estimator, while an unknown id (0) on either side proves nothing
// and is treated as distinct.
bool ptSameSample(RestirPtReservoir current, RestirPtReservoir incoming) {
    // A reset (empty) accumulator must never read as a duplicate: its first
    // vertex carries id 0, which the check below already refuses to match.
    if (current.stats.x <= 0.0 || incoming.stats.x <= 0.0) return false;
    return current.sample.first.sampleId != 0u &&
        current.sample.first.sampleId == incoming.sample.first.sampleId;
}

float ptPathMaxComponent(float3 value) {
    return max(value.r, max(value.g, value.b));
}

// ---- Reservoir update -------------------------------------------------------

// Weighted reservoir sampling of one candidate path.  The weight is the ratio
// of the target density to the density that produced the path, both in solid
// angle, so it is dimensionless.  The stored radiance is never touched here.
// `sourceIdentity` is the caller's stable id for the light the suffix ends on
// (or the sky-state revision for an escaped path; 0 when unknown); it is only
// compared during temporal reuse.
RestirPtReservoir ptAddPath(RestirPtReservoir reservoir, RestirPtPath path,
        uint sourceIdentity, inout uint seed) {
    if (!ptPathReusable(path)) return reservoir;

    float sourcePdf = ptPathSourcePdf(path);
    float targetPdf = ptPathTargetPdf(path, path.anchorPosition,
        path.anchorNormal);
    if (sourcePdf <= 0.0 || targetPdf <= 0.0) return reservoir;

    float weight = targetPdf / max(sourcePdf, RESTIR_PT_MIN_PDF);
    if (!isFinite(weight)) return reservoir;
    // Firefly guard on the candidate weight.  It is an estimator change, so it
    // is recorded on the record instead of being applied silently.
    bool clamped = weight > RESTIR_PT_MAX_CANDIDATE_WEIGHT;
    weight = clamp(weight, 0.0, RESTIR_PT_MAX_CANDIDATE_WEIGHT);
    if (weight <= 0.0) return reservoir;

    reservoir.stats.x += 1.0;
    reservoir.stats.y += weight;

    if (rand01(seed) * reservoir.stats.y < weight) {
        reservoir.sample = path;
        reservoir.stats.z = targetPdf;
        reservoir.source = uint4(0u, RESTIR_PT_RESERVOIR_FLAG_SAMPLED,
            RESTIR_PT_TECHNIQUE_BSDF_PATH, sourceIdentity);
    }
    if (clamped) reservoir.source.y |= RESTIR_PT_RESERVOIR_FLAG_CLAMPED;
    reservoir.stats.w = min(reservoir.stats.x / RESTIR_PT_M_CAP, 1.0);
    return reservoir;
}

// Shared merge primitive.  `incomingTargetPdf` must already be evaluated at
// the current receiver; `incoming.stats.z` still holds the density at the
// receiver that wrote it, and their ratio is the target part of the shift.
// `shiftJacobian` is the derived change of measure G_new/G_old from
// ptFirstEdgeShiftJacobian, so the candidate ratio becomes
//
//   (p-hat_new / p-hat_old) * (q_old / q_new)
//
// which is exactly the ratio of the stored weight to the weight this candidate
// deserves at the new receiver.  M and the weight sum are scaled by the same
// factor, so clamping M cannot change the resolved weight (W = wSum/(M*p-hat)).
//
// The ratio cap is a firefly guard, not part of the derivation: it biases the
// estimate toward the current sample and is documented as an approximation.
//
// The incoming path is validated here as well, because this is a public entry
// point and a caller can hand it a history record straight out of a GPU
// buffer.  The test is ptPathReusable, not merely ptPathValid: a delta path, a
// non-diffuse first vertex or a zero-density path cannot be re-weighted, and
// letting one through would push a record into the reservoir that the resolve
// then rejects, so the pixel would lose a sample it had instead of keeping the
// one it already held.  A light-traced technique has a different density
// structure and is likewise rejected in v3.
//
// `ageIncoming` is the caller's statement about where the record came from: a
// history record is one frame old (+1), a spatial neighbour is from this frame
// (+0).  It is applied only when the incoming actually wins the merge, so a
// rejected-history frame does not age the sample that stayed.
//
// Source identity is *not* a precondition: WRS merges paths that end on
// different lights, and rejecting them whenever the current winner happens to
// be another light would starve every other emitter in the pixel.  The
// temporal gate (requireSourceMatch) is where an identity mismatch rejects.
RestirPtReservoir ptMergeReservoir(RestirPtReservoir current, RestirPtReservoir incoming,
        float incomingTargetPdf, float shiftJacobian, float ratioCap, float mCap,
        bool ageIncoming, inout uint seed) {
    if (!ptReservoirHasSample(incoming)) return current;
    if (!ptPathReusable(incoming.sample)) return current;
    if (incoming.source.z != RESTIR_PT_TECHNIQUE_BSDF_PATH) return current;
    if (!isFinite(incomingTargetPdf) || incomingTargetPdf <= 0.0) return current;
    // J = G_new / G_old has to be finite and positive: a coincident anchor or
    // a grazing first edge makes the shift degenerate, and the candidate
    // cannot be transported to this receiver.
    if (!isFinite(shiftJacobian) || shiftJacobian <= 0.0) return current;
    // A `current` that is not a usable accumulator — the empty start, or a
    // record read from a corrupt buffer — is reset instead of merged: adding a
    // finite weight to a NaN sum would leave the record permanently unusable,
    // and a positive M with no weight would drag the resolved weight towards
    // zero.
    if (!ptReservoirHasSample(current)) current = ptEmptyReservoir();
    // The module never writes M above RESTIR_PT_MAX_M, so a larger value is a
    // corrupted record, not a heavily reused one.
    if (incoming.stats.x > RESTIR_PT_MAX_M) return current;
    if (ptSameSample(current, incoming)) return current;

    float incomingM = min(incoming.stats.x, mCap);
    if (incomingM <= 0.0) return current;

    float ratio = clamp(incomingTargetPdf / max(incoming.stats.z, RESTIR_PT_MIN_PDF) * shiftJacobian,
        0.0, ratioCap);
    // Dividing by the real M (not by max(M, 1)) keeps the cap exact for a
    // record that carries 0 < M < 1; a floor would silently multiply W by M.
    float selectionScale = incomingM / max(incoming.stats.x, RESTIR_PT_MIN_PDF);
    float incomingWeight = incoming.stats.y * selectionScale * ratio;
    if (!(incomingWeight > 0.0) || !isFinite(incomingWeight)) return current;

    float totalWeight = current.stats.y + incomingWeight;
    if (rand01(seed) * totalWeight < incomingWeight) {
        current.sample = incoming.sample;
        current.stats.z = incomingTargetPdf;
        current.source = incoming.source;
        current.source.y |= RESTIR_PT_RESERVOIR_FLAG_REUSED;
        current.source.x = min(incoming.source.x + (ageIncoming ? 1u : 0u), 0xFFFFu);
    }

    current.stats.x += incomingM;
    current.stats.y = totalWeight;
    if (current.stats.x > RESTIR_PT_MAX_M) {
        float rescale = RESTIR_PT_MAX_M / current.stats.x;
        current.stats.x *= rescale;
        current.stats.y *= rescale;
    }
    current.stats.w = min(current.stats.x / RESTIR_PT_M_CAP, 1.0);
    return current;
}

// Temporal reuse.  Validation goes through contracts/history.slang so this
// pass cannot invent a second notion of valid history: depth, normal, material
// and position agreement all come from historyValidate.  On top of that the
// module rejects a delta path, a non-diffuse first vertex, a terminal
// first-edge path, a zero-density path and a malformed record.
//
// `requireSourceMatch` gates the history on the source identity (emitter for
// EMISSIVE_END paths, sky-state revision for ENVIRONMENT paths).  It is off by
// default: a hard gate rejects history whenever the current winner is another
// light, which starves every other emitter in the pixel and biases the
// reservoir towards whatever this frame sampled first.  ptSourceCompatible
// treats an unknown (0) identity as "no information" rather than a mismatch;
// a caller that tags both sides gets real invalidation on a changed source.
//
// Only temporal reuse ages the record: the age counts frames, and a spatial
// neighbour comes from this frame.
RestirPtReservoir ptTemporalReuse(RestirPtReservoir current, RestirPtReservoir history,
        RestirPtReceiver receiver, HistorySample storedReceiver, float3 originDelta,
        HistoryPolicy policy, bool requireSourceMatch, inout uint seed) {
    if (!ptReservoirHasSample(history)) return current;
    if (!ptPathReusable(history.sample)) return current;

    HistoryStatus status = historyValidate(storedReceiver, receiver.worldPosition,
        receiver.normal, receiver.depth, receiver.materialId, policy);
    if (status != HistoryStatus.Valid) return current;

    if (requireSourceMatch &&
            !ptSourceCompatible(current.source.w, history.source.w)) {
        return current;
    }

    RestirPtReservoir incoming = history;
    incoming.sample = ptRebasePath(history.sample, originDelta,
        receiver.position, receiver.normal);
    if (!ptPathValid(incoming.sample)) return current;

    float targetPdf = ptPathTargetPdf(incoming.sample, receiver.position, receiver.normal);
    if (targetPdf <= 0.0) return current;

    // The change of measure is computed from the stored first vertex: G_old at
    // the anchor that sampled it, G_new at this receiver with the vertex
    // rebased into the current camera-relative space.
    float shiftJacobian = ptFirstEdgeShiftJacobian(history.sample.first,
        history.sample.anchorPosition, originDelta, receiver.position);
    if (shiftJacobian <= 0.0) return current;

    // The age is added by the merge, and only if the history record wins.
    // Doing it here unconditionally aged a *fresh* sample whenever history was
    // rejected, which made ptCandidateIsFresh fail and re-enabled PT+DI
    // double counting.
    return ptMergeReservoir(current, incoming, targetPdf, shiftJacobian,
        RESTIR_PT_TEMPORAL_RATIO_CAP, RESTIR_PT_M_CAP, true, seed);
}

// ---- Visibility -------------------------------------------------------------

// First-edge visibility with the same rules as every other pass: the cheap
// geometric query answers the common case, and only a geometric blocker pays
// for the shared material-filtered walk, which is what makes a cutout hole a
// hole and a stained-glass pane a filter.  The ray is built exactly like
// traceShadowVisibility so both agree on origin and length; `receiver.normal`
// is expected to be oriented towards the viewer by the caller.
//
// v2: this is now applied to EVERY path, including ENVIRONMENT ones.  A fresh
// sky path needs no ray (the edge was traced when the path was sampled), but a
// *reused* sky edge may cross geometry that appeared between the two
// receivers, so the resolve and the spatial acceptance test trace it.
bool ptFirstEdgeVisible<T : ISceneTracer>(T traversal, MaterialAtlas atlas,
        BlockMaterialTable blockMaterials, RestirPtReceiver receiver,
        float3 direction, float distance, float bias, MaterialSamplingOptions options,
        out float3 transmittance, out uint flags) {
    transmittance = float3(1.0);
    flags = 0u;

    float3 normal = safeNormalize(receiver.normal, float3(0.0, 1.0, 0.0));
    float tMax = max(distance - bias, bias);
    Ray ray = makeRay(receiver.position + normal * bias, direction, bias, tMax);

    VisibilityResult quick = traversal.traceVisibility(ray);
    if (quick.visible) {
        transmittance = quick.transmittance;
        flags |= RESTIR_PT_RESOLVE_FLAG_VISIBILITY_TRACED;
        return true;
    }

    SurfaceVisibility filtered = traceFilteredVisibility(traversal, atlas,
        blockMaterials, ray, options);
    flags |= RESTIR_PT_RESOLVE_FLAG_VISIBILITY_TRACED;
    if ((filtered.flags & SURFACE_VISIBILITY_FLAG_OVERFLOW) != 0u) {
        flags |= RESTIR_PT_RESOLVE_FLAG_FILTER_OVERFLOW;
    }
    if (!filtered.result.visible) return false;

    transmittance = filtered.result.transmittance;
    return true;
}

// Spatial reuse.  Same-frame records, so no rebase and no ageing; the
// neighbour must be a usable path on the same surface and its first edge must
// be visible from this receiver.  v3 reuses only diffuse indirect paths, so a
// sky or emitter edge never reaches the merge; the visibility test still runs
// because the stored edge may cross geometry that is between the two receivers
// or moved since the neighbour was sampled.  The transmittance is deliberately
// not baked into the candidate: the material-filtered resolve walks the same
// edge again, and tinting here as well would square the glass.
//
// The caller owns neighbour selection and must not pass the same reservoir
// twice: after a merge that did not select the neighbour, the module has no
// way to tell a duplicate from a second draw of the same path, and the
// duplicate's weight is added again.  A fixed, distinct set of neighbours per
// pixel is the contract.
RestirPtReservoir ptSpatialReuse<T : ISceneTracer>(RestirPtReservoir current,
        RestirPtReservoir neighbor, RestirPtReceiver receiver, T traversal,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials, float bias,
        MaterialSamplingOptions options, inout uint seed) {
    if (!ptReservoirHasSample(neighbor)) return current;
    if (!ptPathReusable(neighbor.sample)) return current;
    if (!ptReuseCompatible(receiver, neighbor.sample, RESTIR_PT_SPATIAL_MAX_DISTANCE,
            RESTIR_PT_SPATIAL_MIN_NORMAL_DOT)) {
        return current;
    }

    float3 toVertex = neighbor.sample.first.position - receiver.position;
    float distanceSquared = dot(toVertex, toVertex);
    if (!isFinite(distanceSquared)) return current;
    if (distanceSquared <= RESTIR_PT_MIN_DISTANCE * RESTIR_PT_MIN_DISTANCE) return current;

    float distance = sqrt(distanceSquared);
    float3 direction = toVertex / distance;

    float3 transmittance;
    uint visibilityFlags;
    if (!ptFirstEdgeVisible(traversal, atlas, blockMaterials, receiver, direction,
            distance, bias, options, transmittance, visibilityFlags)) {
        return current;
    }

    float targetPdf = ptTargetPdf(neighbor.sample.first, receiver.position,
        receiver.normal);
    if (targetPdf <= 0.0) return current;

    // Same frame: the vertex is already in the current camera-relative space,
    // so the shift is the pure anchor change and originDelta is zero.
    float shiftJacobian = ptFirstEdgeShiftJacobian(neighbor.sample.first,
        neighbor.sample.anchorPosition, float3(0.0), receiver.position);
    if (shiftJacobian <= 0.0) return current;

    RestirPtReservoir incoming = neighbor;
    incoming.sample = ptShiftPath(neighbor.sample, receiver.position, receiver.normal);
    return ptMergeReservoir(current, incoming, targetPdf, shiftJacobian,
        RESTIR_PT_SPATIAL_RATIO_CAP, RESTIR_PT_M_CAP, false, seed);
}

// A path that was blocked this frame must lose weight instead of vanishing, so
// the reservoir can recover when the blocker moves.  This is the one place
// that deliberately does not preserve W: decaying the weight sum while leaving
// M alone is a lag/firefly tradeoff, not a resampling step.  The flag is
// cleared on a visible frame, otherwise a reservoir that recovered would still
// look occluded.
RestirPtReservoir ptDecayOnOcclusion(RestirPtReservoir reservoir, bool occluded) {
    if (!occluded) {
        reservoir.source.y &= ~RESTIR_PT_RESERVOIR_FLAG_OCCLUDED;
        return reservoir;
    }
    if (!isFinite(reservoir.stats.y)) return reservoir;
    reservoir.stats.y *= RESTIR_PT_OCCLUSION_DECAY;
    reservoir.source.y |= RESTIR_PT_RESERVOIR_FLAG_OCCLUDED;
    // Sticky until a new sample is selected: the weight sum no longer matches
    // M * p-hat, so the record must not pass a freshness test that assumes it
    // does.
    reservoir.source.y |= RESTIR_PT_RESERVOIR_FLAG_DECAYED;
    return reservoir;
}

// ---- Resolve ----------------------------------------------------------------

// Resolves the stored path at the given receiver.  The target density is
// recomputed here (not read from stats.z) so the weight is normalised at the
// receiver that is actually being shaded; the reservoir is not modified.
//
// `radiance` is the suffix radiance and `weight` the density ratio, so the
// caller shades once: f_cur * radiance * weight.  The two clamps are firefly
// guards, not correctness terms; they raise their flags so a caller can see
// that the estimate was capped instead of silently losing energy.  The
// radiance clamp preserves chroma: contracts/math.slang's clampRadiance clamps
// per channel, which shifts hue, so the module scales the whole vector instead.
RestirPtResolveResult ptResolveDetailed(RestirPtReservoir reservoir,
        float3 surfacePosition, float3 surfaceNormal) {
    RestirPtResolveResult result = ptEmptyResolveResult();
    if (!ptReservoirHasSample(reservoir)) return result;
    if (!ptPathValid(reservoir.sample)) return result;
    if ((reservoir.sample.flags & RESTIR_PT_PATH_FLAG_DELTA) != 0u) return result;
    // A path the sampler never chose must not contribute, and a record with a
    // zero first-edge density cannot be one this module produced.
    if (reservoir.sample.first.pdf <= 0.0) return result;

    float3 toVertex = reservoir.sample.first.position - surfacePosition;
    float distanceSquared = dot(toVertex, toVertex);
    if (!isFinite(distanceSquared)) return result;
    if (distanceSquared <= RESTIR_PT_MIN_DISTANCE * RESTIR_PT_MIN_DISTANCE) return result;

    float targetPdf = ptTargetPdf(reservoir.sample.first, surfacePosition,
        surfaceNormal);
    if (targetPdf <= 0.0) return result;

    // No floor on M: the normalisation is exactly wSum / (M * p-hat), the same
    // form restir_di.slang uses.  A record whose M survived the MAX_M rescale
    // can carry 0 < M < 1, and flooring it at 1 would silently shrink W.
    float weight = reservoir.stats.y /
        max(reservoir.stats.x * targetPdf, RESTIR_PT_MIN_PDF);
    if (!isFinite(weight) || !(weight > 0.0)) return result;

    result.valid = true;
    result.direction = toVertex * rsqrt(distanceSquared);
    result.distance = sqrt(distanceSquared);
    result.targetPdf = targetPdf;
    result.emitterIdentity = reservoir.source.w;
    result.reservoirFlags = reservoir.source.y;

    if ((reservoir.sample.flags & RESTIR_PT_PATH_FLAG_ENVIRONMENT) != 0u) {
        result.flags |= RESTIR_PT_RESOLVE_FLAG_ENVIRONMENT;
    }

    float3 radiance = reservoir.sample.first.throughput;
    float maximum = ptPathMaxComponent(radiance);
    if (maximum > RESTIR_PT_MAX_RADIANCE) {
        radiance *= RESTIR_PT_MAX_RADIANCE / maximum;
        result.flags |= RESTIR_PT_RESOLVE_FLAG_RADIANCE_CLAMPED;
    }
    if (weight > RESTIR_PT_MAX_RESOLVED_WEIGHT) {
        weight = RESTIR_PT_MAX_RESOLVED_WEIGHT;
        result.flags |= RESTIR_PT_RESOLVE_FLAG_WEIGHT_CLAMPED;
    }

    result.radiance = radiance;
    result.weight = weight;
    result.transmittance = float3(1.0);
    return result;
}

// Resolve fields only.  The obligatory form has no normal argument, so the
// normal stored in the anchor is used; a normal-mapped receiver should call
// ptResolveDetailed or ptResolveFiltered with its true normal instead.
bool ptResolve(RestirPtReservoir reservoir, float3 surfacePosition,
        out float3 radiance, out float3 direction, out float weight) {
    RestirPtResolveResult result = ptResolveDetailed(reservoir, surfacePosition,
        reservoir.sample.anchorNormal);
    radiance = result.radiance;
    direction = result.direction;
    weight = result.valid ? result.weight : 0.0;
    return result.valid;
}

// Geometric-only resolve through ISceneTracer.traceVisibility.  Cheapest form;
// it treats a leaf card or a glass pane as a blocker, so use it only where the
// extra traversal cost matters more than correctness at foliage.
//
// v2: sky paths are traced like every other path — the edge to the recorded
// escape point is a real ray with real occluders once the path is reused.
RestirPtResolveResult ptResolveVisible<T : ISceneTracer>(RestirPtReservoir reservoir,
        T traversal, RestirPtReceiver receiver, float bias) {
    RestirPtResolveResult result = ptResolveDetailed(reservoir, receiver.position,
        receiver.normal);
    if (!result.valid) return result;

    float3 normal = safeNormalize(receiver.normal, float3(0.0, 1.0, 0.0));
    Ray ray = makeRay(receiver.position + normal * bias, result.direction, bias,
        max(result.distance - bias, bias));
    VisibilityResult visibility = traversal.traceVisibility(ray);
    result.flags |= RESTIR_PT_RESOLVE_FLAG_VISIBILITY_TRACED;
    result.valid = visibility.visible;
    result.transmittance = visibility.visible ? visibility.transmittance : float3(0.0);
    if (!visibility.visible) {
        result.radiance = float3(0.0);
        result.weight = 0.0;
    }
    // A backend that returns a non-finite tint must not reach the image or be
    // handed on as a filter: every field this function reports is sanitised,
    // not only the radiance.
    if (!isFinite(result.transmittance)) result.transmittance = float3(0.0);
    result.radiance *= result.transmittance;
    if (!isFinite(result.radiance) || !isFinite(result.weight)) {
        result.valid = false;
        result.radiance = float3(0.0);
        result.weight = 0.0;
        result.transmittance = float3(0.0);
    }
    return result;
}

// Resolve with the first edge revalidated through the shared material-filtered
// visibility walk.  The suffix itself is not re-traced: that is the assumption
// a reused path already carries.  v2: sky paths are included — their edge is
// traced to the recorded escape point.
RestirPtResolveResult ptResolveFiltered<T : ISceneTracer>(RestirPtReservoir reservoir,
        T traversal, MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RestirPtReceiver receiver, float bias, MaterialSamplingOptions options) {
    RestirPtResolveResult result = ptResolveDetailed(reservoir, receiver.position,
        receiver.normal);
    if (!result.valid) return result;

    float3 transmittance;
    uint visibilityFlags;
    if (!ptFirstEdgeVisible(traversal, atlas, blockMaterials, receiver,
            result.direction, result.distance, bias, options, transmittance,
            visibilityFlags)) {
        result.valid = false;
        result.radiance = float3(0.0);
        result.weight = 0.0;
        result.transmittance = float3(0.0);
        result.flags |= visibilityFlags;
        return result;
    }

    result.flags |= visibilityFlags;
    result.transmittance = isFinite(transmittance) ? transmittance : float3(0.0);
    result.radiance *= result.transmittance;

    if (!isFinite(result.radiance) || !isFinite(result.weight)) {
        result.valid = false;
        result.radiance = float3(0.0);
        result.weight = 0.0;
        result.transmittance = float3(0.0);
    }
    return result;
}

// ---- Multiple importance sampling ------------------------------------------

// True only for a reservoir that holds a single candidate sampled this frame,
// never merged, capped, occluded or decayed, and resolved at the very receiver
// that sampled it.  That is the only state in which the resolved weight is
// exactly 1/p_source and `first.pdf` is the sampling density at the current
// receiver, which is what a MIS mix needs.  After any merge W is a mixture and
// the stored pdf belongs to the receiver that wrote the record, so a MIS mix
// with DI would not be combining two estimates on the same measure.
//
// OCCLUDED is rejected because it means this frame's visibility test failed:
// the direct term that MIS would combine is still present, so the two
// contributions no longer describe the same integral.  DECAYED is rejected
// because the decay scaled the weight sum, so wSum/(M*p-hat) is not the
// sampling density ratio any more.
bool ptCandidateIsFresh(RestirPtReservoir reservoir, float3 surfacePosition) {
    if (!ptReservoirHasSample(reservoir)) return false;
    if (reservoir.stats.x > 1.0) return false;
    if (reservoir.source.x != 0u) return false;
    if ((reservoir.source.y & (RESTIR_PT_RESERVOIR_FLAG_REUSED |
                               RESTIR_PT_RESERVOIR_FLAG_CLAMPED |
                               RESTIR_PT_RESERVOIR_FLAG_OCCLUDED |
                               RESTIR_PT_RESERVOIR_FLAG_DECAYED)) != 0u) {
        return false;
    }
    float3 anchorDelta = reservoir.sample.anchorPosition - surfacePosition;
    if (!isFinite(anchorDelta)) return false;
    return dot(anchorDelta, anchorDelta) <=
        RESTIR_PT_FRESH_ANCHOR_DISTANCE * RESTIR_PT_FRESH_ANCHOR_DISTANCE;
}

// Balance-heuristic weight for one of two strategies that both sample the same
// path.  `ownPdf` is the density of the strategy that produced the sample
// being weighted, `otherPdf` the density the other strategy assigns to that
// same path; the probabilities let a caller run unequal technique budgets.  A
// degenerate total means neither strategy assigns the path any density, which
// is a caller error, not a zero contribution; ptCombineDirectMis treats it as
// such.
float ptMisWeight(float ownPdf, float otherPdf, float ownProbability,
        float otherProbability) {
    float own = max(ownPdf, 0.0) * max(ownProbability, 0.0);
    float other = max(otherPdf, 0.0) * max(otherProbability, 0.0);
    float total = own + other;
    return total > RESTIR_PT_MIN_PDF ? own / total : 0.0;
}

// One of the two estimates of a receiver -> emitter edge, with *both*
// densities evaluated at this term's own sampled direction:
//
//   bsdfPdf   receiver lobe density of this direction (BSDF-sampling strategy)
//   lightPdf  emitter sampler density of this direction (light-sampling strategy)
//
// Carrying both is what makes the balance heuristic correct.  Weighting a pair
// of samples with the other sample's density — one pdf per contribution — is
// only valid while the two directions coincide, and the direction a BSDF
// sample and a light sample pick are independent draws, not the same
// direction.  The caller can evaluate both densities for both directions
// because it owns the lobe and the emitter sampler; the module owns neither
// and cannot do it here.
struct RestirPtMisTerm {
    float3 contribution;
    float  bsdfPdf;
    float  lightPdf;
    uint   emitterIdentity;  // 0 = unknown
    float  probability;      // technique selection probability, not a density
};

// Who owns the first-edge emitter-terminated term when PT and DI both
// estimate it.  The overlap is exactly this term: a path with two or more
// edges is indirect and no DI light sample estimates it, and a DI sample of a
// direct hit is never a path suffix.
//
//   PT_DIRECT_SUM   legacy: both contributions are summed.  Correct only when
//                   the two terms are proven distinct (different known
//                   identities) or when only one side is present.
//   DI_OWNER        default: the direct term belongs to DI.  When the strict
//                   MIS conditions hold, the term is combined by the balance
//                   heuristic; otherwise the PT first-edge contribution is
//                   dropped and DI's stands.  The PT reservoir keeps its
//                   indirect paths untouched — dropping them here would delete
//                   exactly the energy only this module produces.
//   PT_OWNER        the mirror policy for a caller that runs PT with DI
//                   disabled for emitters outside the DI list.
enum RestirPtDirectOwner : uint {
    RestirPtDirectOwner_DIOwner = 0u,
    RestirPtDirectOwner_PTOwner = 1u,
    RestirPtDirectOwner_Sum     = 2u,
};

// Combines a resolved PT contribution with a DI light-sampling contribution
// for the same first-edge direct term, under the chosen ownership policy.
//
// Preconditions, all checked by the caller: both contributions shade the same
// receiver, both estimate the same receiver -> emitter term (so the PT suffix
// must terminate on that emitter, not just on some emitter), every density is
// a solid-angle density at the *current* receiver, and — for the MIS branch —
// the PT reservoir passes ptCandidateIsFresh with the position being resolved.
//
// The identity is the safety net for the "same term" condition, and it is only
// usable when both sides are known: identity 0 means "not tagged" in this
// module, so an unknown pair cannot be proven to be the same term and cannot
// be proven to be different ones either.  That is exactly why the ownership is
// a policy and not an automatic sum: under DI_OWNER an unprovable pair yields
// DI's term (no double count), under Sum it yields the sum (a double count the
// caller accepted).  Tag every path: reserve 0 and offset real ids by one.
//
// This never merges the two reservoirs; DIReservoir and RestirPtReservoir
// keep their own policies and their own buffers.
float3 ptCombineDirectMis(RestirPtMisTerm pt, RestirPtMisTerm direct,
        RestirPtDirectOwner owner) {
    bool sameKnownSource = pt.emitterIdentity != 0u && direct.emitterIdentity != 0u &&
        pt.emitterIdentity == direct.emitterIdentity;
    // A NaN density or probability is not "usable": multiplying by it would
    // turn one bad term into a NaN image, and the balance heuristic is
    // meaningless when it cannot read both inputs.
    bool usable = isFinite(pt.bsdfPdf) && isFinite(pt.lightPdf) &&
        isFinite(direct.bsdfPdf) && isFinite(direct.lightPdf) &&
        isFinite(pt.probability) && isFinite(direct.probability) &&
        pt.bsdfPdf > RESTIR_PT_MIN_PDF && direct.lightPdf > RESTIR_PT_MIN_PDF &&
        pt.probability > 0.0 && direct.probability > 0.0;

    float3 sum = pt.contribution + direct.contribution;
    bool sumFinite = isFinite(sum);

    if (owner == RestirPtDirectOwner.RestirPtDirectOwner_Sum) {
        return sumFinite ? sum : float3(0.0);
    }

    bool misApplies = sameKnownSource && usable;
    if (owner == RestirPtDirectOwner.RestirPtDirectOwner_PTOwner) {
        // PT owns the term: combine when MIS applies, otherwise keep the PT
        // contribution and let the caller suppress its DI sample.
        if (!misApplies) return isFinite(pt.contribution) ? pt.contribution : float3(0.0);
    } else {
        // DI_OWNER (default): combine when MIS applies, otherwise DI's term
        // stands and the PT first-edge contribution is dropped.  The indirect
        // energy of the PT reservoir never passes through this function.
        if (!misApplies) return isFinite(direct.contribution) ? direct.contribution : float3(0.0);
    }

    float ptWeight = ptMisWeight(pt.bsdfPdf, pt.lightPdf, pt.probability,
        direct.probability);
    float directWeight = ptMisWeight(direct.lightPdf, direct.bsdfPdf,
        direct.probability, pt.probability);
    // Both weights collapse to zero only when neither side clears the MIN_PDF
    // floor, and returning zero there would delete a term that is lit; the sum
    // keeps the energy and at worst double counts a term the caller claimed
    // was shared under the same known identity.
    if (!(ptWeight + directWeight > 0.0)) {
        return sumFinite ? sum : float3(0.0);
    }

    float3 combined = pt.contribution * ptWeight + direct.contribution * directWeight;
    return isFinite(combined) ? combined : float3(0.0);
}

// True when the stored path ends on an emitter at its first scattered vertex,
// which is the only case where the direct term overlaps a DI light sample.
bool ptTerminatesOnEmitter(RestirPtReservoir reservoir) {
    return (reservoir.sample.flags & RESTIR_PT_PATH_FLAG_EMISSIVE_END) != 0u;
}

// True when the stored path escaped to the sky at its first edge.  v2: such a
// path is *not* exempt from first-edge visibility any more (see the resolve
// and spatial-reuse comments); this predicate is for callers that want to
// apply the sky-source identity gate.
bool ptEscapedToEnvironment(RestirPtReservoir reservoir) {
    return (reservoir.sample.flags & RESTIR_PT_PATH_FLAG_ENVIRONMENT) != 0u;
}
