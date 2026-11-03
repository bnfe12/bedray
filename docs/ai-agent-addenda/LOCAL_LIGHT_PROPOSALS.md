# Receiver-local light proposals — 2026-11-03

The old proposal preferred the front of a camera-importance-sorted list even
when shading a surface near another light. BEM2 adds a balanced light BVH over
the existing published emitter table. This implements receiver-local sampling;
it is not ReGIR or a claim of measured quality/FPS improvement.

## Proposal and estimator

Each node bounds complete affine emitter quads and stores a bounded power proxy:
area × mean emission × source boost, with entity tint where available.
A child's importance is power / (1 + squared distance from receiver to its AABB).
Selection follows normalized child importance. If both weights underflow, the
conditional distribution is one half. It is a proposal, not a new transport law.

The distribution is a mixture: 1/8 of the original global rank distribution and
7/8 of the receiver-local tree. For record i, both sampling and re-evaluation use
q(i|x) = globalFraction × globalPMF(i) + (1-globalFraction) × localPMF(i|x).
The global component retains full support over the uploaded list. The area-to-
solid-angle Jacobian and the existing RIS weighting consume this complete PMF.
Fresh draws and temporal/spatial imports therefore use the same probability law.
The existing radiance kernel, gains, candidate count and ray count are retained.

Sampling walks down at most 16 edges. Re-evaluation walks parent links from the
record's leaf. Physical importance order and stable IDs remain separate.
No scan through all lights is introduced on the GPU. Missing IDs still reject
history; changed animated lights are not motion-remapped by this mechanism.

The importance/discrete-PMF contract follows the approach described by
[PBRT light sampling](https://pbr-book.org/4ed/Light_Sources/Light_Sampling).
[RTXDI](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/Integration.md) defines
ReGIR as grid reservoir sampling, which this tree deliberately is not called.

## Publication, layout and cost

BEM2 contains two uint4 headers, 112-byte records, a half-full uint4 ID index,
and 2N-1 nodes of 48 bytes. The second header stores node count, node stride,
global mixture fraction and a reserved zero word. Each record's ID metadata
also names its leaf node. Nodes store min/power, max, and left/right/parent/leaf.

The host constructs a balanced hierarchy from Morton-ordered quad centres.
When the selected stable-ID set is unchanged, it reuses the committed leaf
order, maps IDs to their new physical slots and refits bounds without sorting.
Identity state and topology commit together only after successful publication.
Exact recording proof still retires immutable table generations.

Maximum storage is 15 MiB - 16 bytes at 65536 lights, versus BEM1's 9 MiB + 16.
The same native memory owner/budget covers it. Native recording validates its
exact extent/count before commands. Public ABI-v2 layouts, descriptor bindings,
capture payload and DI reservoir layout remain unchanged. Pair the updated
JAR/core; this core rejects BEM1-sized tables.

Diagnostic A/B: -Dbedray.rt.disableLocalLightSampling=true selects the original
global distribution (fraction 1) in the same BEM2 layout. It preserves the old
draw count/sequence. No resolution, SPP or visibility budget is changed.
The bounded VKB-EMITTERS log includes fraction, table bytes, tree depth,
topology reuse, identity changes and lookup probe length.

## Checks and remaining acceptance

Host regressions cover bounds/parent/leaf consistency, topology reuse after
reorder/reanchor, finite power, maximum extent/depth and rejected overflowing
quad bounds. Production Slang CPU cases check normalized PMFs, receiver-local
preference, empirical selection frequency, sample/evaluation PDF agreement,
inverse-PMF estimation, empty/single-light cases and global reference behavior.
AOT/native recording checks cover the shared private layout.

Only the captured light set is sampled; this does not make host collection
complete outside its current range or add missing world/dynamic lights.
Animated-light motion references, ReGIR, RC guiding, estimator variance/bias,
glass traversal and RTX visual/performance acceptance remain separate work.
Compare actual gameplay captures with identical settings before claiming a
gain. Native 1080p/60 and the CPU cost of entity-driven republishing are unmeasured.
