# Emitter table identity — 2026-11-03

BEM1 below records the initial identity layout. The live format is now BEM2;
[receiver-local proposals](LOCAL_LIGHT_PROPOSALS.md) specify its additional
header, leaf references, spatial tree, mixture PMF and memory bound.

The previous reservoir stored the physical index of an importance-sorted light.
The scan fingerprint did not describe the published selection/order, and entity
lights were added after it. Re-evaluation wrapped a disappeared index modulo the
current count and accepted two UV projections without testing the emitter plane.
An old sample could therefore refer to a different light.

## One published identity contract

`EmitterTable` owns exact content keys and monotonically assigned, nonzero
32-bit IDs. Reordering, importance changes and coordinate reanchoring preserve
an unchanged emitter's ID. World corners use double precision in the CPU key;
all 24 captured payload words participate. Duplicate quads have distinct IDs.
Changed/disappeared emitters lose their old IDs; IDs are never recycled during
the owner's lifetime. Exhaustion is an explicit failure before publication.
An atlas generation change invalidates the affected identity namespace too.

The private BEM1 storage buffer contains:

| Region | Layout |
|---|---|
| Header | uint4: magic 0x42454D31, count, index capacity, record bytes (112) |
| Each record | Six captured float4 payloads (96 bytes), then uint4 (stable ID and zero padding) |
| Index | Power-of-two array of uint4 (ID, current physical slot, zero padding), at most half full |

The binding is `StructuredBuffer<uint4>`. The shader validates the header and
extent, samples in the existing physical importance order with its existing
proposal PDF, and stores the stable ID in the reservoir's existing light-index
lane. Temporal/spatial re-evaluation resolves that ID through the current index.
Missing IDs are rejected. There is no modulo fallback or search through all
emitters. Probing is bounded by the declared index capacity.

The maximum table is 9 MiB + 16 bytes for 65536 emitters. The native recorder
validates its exact extent/count before recording. Public ABI-v2 structures,
descriptor binding numbers and the 64-byte DI reservoir layout are unchanged.
The new core rejects an old flat emitter table; ship paired JAR/core artifacts.

## Publication, history and geometry

`EmitterCaptureBuffers` prepares identities and complete bytes before allocation,
publishes only after a successful copy, and commits the identity map with the
new generation. Failed allocation/copy preserves the previous map and buffer.
Every recording retains its exact immutable buffer generation until host
completion/discard proof. No native submission or wait is introduced.

DI no longer clears its complete ring for a light-list change. Only samples
whose IDs remain in the current table can be imported. Its explicit/settings/
extent resets remain. Cached PT suffixes still invalidate when selected light
content changes; mere reordering does not change that revision.

Re-evaluation solves affine quad coordinates using the Gram matrix and rejects
degenerate/out-of-range/off-plane points before sampling the texture. This also
handles skewed entity quads. It preserves the existing estimator, gains, falloff,
visibility layers and ray count.

`[VKB-EMITTERS]` reports table version, selected/retained/added/removed counts,
bytes and maximum lookup probe length at the existing bounded logging interval.
`[VKB-RESTIR]` identifies explicit/settings DI resets.

The approach rejects changed geometry rather than maintaining previous/current
animated light geometry. Stable primitive IDs and motion-aware light references
remain a separate extension. NVIDIA's [application bridge](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/RtxdiApplicationBridge.md#rab_translatelightindex)
and [integration guide](https://github.com/NVIDIA-RTX/RTXDI/blob/main/Doc/Integration.md#lightbuffer)
describe that fuller previous/current mapping contract.

## Acceptance limits

Regression coverage exercises reorder/reanchor, same-size replacement, tint/
geometry changes, disappearance/reintroduction, duplicate quads, large world
coordinates, collisions, bounded layout and publication rollback. Production
Slang CPU cases exercise lookup and affine/plane rejection; AOT/native tests
cover the shared shader and recording contracts.

No RTX visual or FPS result is available on this Intel UHD host. Many-light
motion/edit scenes and dynamic emitters still need GPU acceptance. Receiver-local
proposals/ReGIR, estimator variance/bias and glass traversal cost are not solved
by identity repair, and native 1080p/60 remains unmeasured.
