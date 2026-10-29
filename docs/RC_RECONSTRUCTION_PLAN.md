# RC v2 finite-window reconstruction

The v2 field is connected to the native path-trace group. It is an opt-in,
current-frame estimator of reflected direct light for a primary diffuse
continuation. It does not alias the v1 two-segment atlas or its direction set.
The user explicitly authorized implementing RC shader mathematics on 2026-10-29.

Enable explicitly with `vkbridge.rt.nativePathTrace=true`,
`vkbridge.rt.nativeScene=true`, and `vkbridge.rt.radianceCascades=true`.
Select `bedray.pathTracer=slang` when a denoiser is enabled; its AUTO gate remains.
`vkbridge.rt.rcBaseAxis` accepts 8 (default), 16 or 32. Missing native ownership,
AOT contracts, enabled capabilities or matching exports fail explicitly. The
ordinary native path remains opt-in pending owner live acceptance.

## Mapping and transport

Five levels share one raw-camera-relative center. A fresh field is built per
recording; there is no temporal field history or independently snapped grid.
At level c, spacing is 2^c, allocated axis is max(1,N>>c)+2, and angular side
is 2^c on each of six cubemap faces. Each angular cell has four nested children.
The corner winding and face mapping live in field_mapping.slang. Spherical
triangle areas supply the angular weights; the complete sphere sums to 4*pi.
The one-probe halo guarantees eight-neighbor support for finer probe centers.

Near/far radii are 4*spacing and 8*spacing. Trace the terminal level, then
reconstruct levels 3,2,1,0. For each parent angular cell, angular child and
positive-weight coarse spatial neighbor, trace the bridge from
`parent + near[c]*childDirection` to `neighbor + near[c+1]*childDirection`.
Compose that bridge with its associated coarse tail **before averaging**.
The bridge excludes its far endpoint using the preceding representable float
distance; the coarse tail owns its start. Endpoints must be finite and forward.
Do not also add the old parent interval: this bridge replaces it.

Every required branch must resolve. Zero-weight branches need no read; exactly
opaque fronts make their back irrelevant. Small positive transmission does not.
Missing/invalid branches are never dropped and renormalized. Complete quadrature
weights are normalized only for floating-point roundoff. Confidence is minimum
metadata, not energy. L/T compose as L_front + T_front*L_back and T_front*T_back.

## Gather and ownership of light sources

A visible primary diffuse continuation connects to the eight finest neighbors.
The connection owns the near field, and its composed tail supplies the rest.
Shifted endpoint-cell corners are clipped to the shading hemisphere; spherical
polygon vector area integrates cosine over each apparent cell. The endpoint
mesh must contain the gather origin. Constant incoming radiance integrates to
pi*L. The gather returns irradiance/pi; the existing diffuse BSDF weight applies
the material and lobe-selection probability exactly once.

The primary path already owns direct emitter NEE. The field therefore zeros
surface emission gain while retaining emission masks and the light sampler's
emitter radiance: its stored source is **reflected direct lighting**, one
indirect bounce. Both field-build and near-field traces use that policy, so a
directly visible emitter is not added again by the diffuse continuation.
Primary emission/NEE, specular/transmission and water continuations keep their
existing path ownership. The one-bounce depth setting does not enable RC.

Outside the finite spatial support the ordinary path continues and sets the
RC_OUTSIDE diagnostic. A required unresolved field sets RC_UNRESOLVED; the
sample is excluded from accumulation, separately from non-finite faults.
RC_FINITE_WINDOW and RC_TERMINAL distinguish a bounded estimate from one whose
surviving transport is known. A terminal bounded miss is not proof of open sky.
No environment is inserted without a real boundary provider. No field temporal
reuse is implemented or advertised.

This is a discretized cone estimator with bent bridge paths. It is not an exact
shifted straight-ray integral, an infinite-range GI solution or a measured
real-time performance claim. Existing RC trace material/visibility limits still
apply; this transfer does not introduce refraction or a new participating-medium
solver.

## Native and ABI boundary

The additive BedrayRCDesc is 32 bytes. Existing ABI-v2 layouts and render calls
stay unchanged. PathTracePass recognizes only the native 28-binding RC variant;
34 is a 272-byte metadata UBO and 35 a 32-byte-per-cell tail buffer. The builder
uses 16 bindings, an 80-byte reflected push block and 64 threads. The native
owner records five dependent dispatches and their barriers before primary
tracing. The existing opaque graph node owns this complete chain; no second
barrier/history authority is introduced.

Metadata and tails are separate immutable per-recording allocations, pinned
until exact host completion/discard. Partial failures and unknown submission
outcomes retain the allocations. Eight pending records are bounded by a shared
buffer budget (192 MiB default). Device loss retains the pool until all covered
host recording proofs arrive. Native never submits or waits.

N=8/16/32 use 69,168 / 145,776 / 653,040 tails respectively, or 2,213,376 /
4,664,832 / 20,897,280 bytes per recording plus 272 metadata bytes. Default N=8
requires up to 886,272 lower-level bridge traces, 41,472 terminal traces, and up
to 48 near-field connections per covered diffuse pixel, before surface/shadow
walk work. GPU performance and visual quality remain unverified.

## Verification

The independent Java reference checks transport algebra, same-ray endpoint
ownership, complete branch weights, spatial support, nested angular areas,
occlusion/radiance correlation and shifted projected gather. Its 16 numerical
cases include 6,291,456 dense integration samples: maximum absolute projected
integral error was 0.00096494 against tolerance 0.002. Constant irradiance pi
checks pass to 2e-14. Those are CPU-reference results, not executed GPU shaders.

Native tests exercise the five-dispatch chain, borrowed ranges, distinct
per-recording buffers, slots, failure positions, wrong completion identities,
discard and device loss, including the enclosing path group. CI
[40937161641](https://github.com/bnfe12/bedray/actions/runs/40937161641)
passed 25 core + one scene + one lifetime test on
`7dfdf5ca1d9f043b7fcf4f7c79bf314e3cbc7bba`.
Clean source `67adc69b147e0a64ad0dfef6b814464285a7e942` passed the full Java
suite: 861 tests, 852 passed, nine skipped, zero failures/errors (1m50s), with
all 26 AOT entries. Final geometric-face offset source
`eb7659aeedb2295fec29822e4811b1fe93288ad0` rebuilt all 26 entries with Slang
2026.20 and passed all 46 focused RC/FFM/ray-offset/rejection/anchor tests
(55s). Native sources are unchanged from the CI revision above. No new
GPU/game/visual or owner acceptance is claimed.

## Primary references

The transport composition and connection-before-interpolation approach follows
[Sannikov's paper source](https://github.com/Raikiri/RadianceCascadesPaper/blob/main/RadianceCascades.tex)
and [Osborne/Sannikov, Appendix A](https://academic.oup.com/rasti/article/doi/10.1093/rasti/rzae062/7929002).
The cubemap discretization, finite-window source policy, bounds, ABI and validity
rules above are Bedray implementation decisions, not results claimed by those
papers.
