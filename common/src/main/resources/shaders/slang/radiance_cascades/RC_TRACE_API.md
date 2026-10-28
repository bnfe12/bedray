# Typed Radiance Cascades trace API

Status: source-only, compile-checked with `slangc` 2026.20 (L1).  There is no
GPU execution or live-frame evidence in this repository.  The typed path is not
wired into the host render loop yet.

This document is the contract between a host pass and the typed Radiance
Cascades trace.  The implementation is split by responsibility; the public
facade is [`rc_trace.slang`](rc_trace.slang).

## Module layout

```
rc_trace.slang              public facade (re-exports the six modules below)
rc_trace_options.slang      options, events, result, environment policy
rc_trace_shade.slang        first-surface emission + NEE
rc_trace_walk.slang         segment finalization + interval walk
rc_trace_dispatch.slang     store, probe and dispatch entries
rc_trace_history.slang      provisional temporal history (opt-in)
rc_trace_ownership.slang    distance-band proposal (opt-in)
```

Dependency direction is one-way:

```
options <- shade <- walk <- dispatch <- history
options <- ownership
```

`rc_trace.slang` re-exports the whole surface, so a host pass normally imports
only the facade.  A direct import of one module also works when the caller
imports the contract types named by that module's signatures; a plain `import`
is not re-exported.

The trace does not import the legacy float4 store or the obsolete
`rc_trace_segments.slang`.

## Record model

`RCSegment` is defined by
[`interval_transport.slang`](interval_transport.slang):

```slang
struct RCSegment {
    float3 radiance;       // segment-local L
    float  confidence;     // status metadata, never an interpolation weight
    float3 transmittance;  // segment-local T
    uint   flags;          // RC_SEGMENT_*
};
```

The reflected layout is 32 bytes.  The host must verify that stride; the shader
cannot check it.

Flags:

| Flag | Meaning |
|---|---|
| `RC_SEGMENT_RESOLVED` | the record is a usable observation |
| `RC_SEGMENT_BLOCKED` | an opaque/zero-transmission event ends this segment |
| `RC_SEGMENT_BOUNDED_MISS` | the segment traversed its window without environment |
| `RC_SEGMENT_ENVIRONMENT` | this segment owns the sky sample; nothing may compose behind it |
| `RC_SEGMENT_TRUNCATED` | a budget ended the segment; not a resolved value |
| `RC_SEGMENT_CLAMPED` | the firefly bound changed the value |

Composition is always:

```
L_total = L_near + T_near * L_far
T_total = T_near * T_far
```

`rcComposeSegments` enforces three rules:

- an invalid front returns unresolved (with `TRUNCATED` preserved);
- exactly zero front transmittance returns the front unchanged;
- an `ENVIRONMENT` front cannot take a back segment.

Do not average the two records by confidence.  Confidence is validity metadata;
an opaque near interval must extinguish the far interval regardless of either
confidence value.

## Options

```slang
RCTraceOptions options = defaultRCTraceOptions();
```

| Field | Default | Contract |
|---|---|---|
| `maxDepth` | `2` | maximum translucent-emission events per ray |
| `rayBias` | `1.0e-3` | must be finite and `> 0` |
| `nearDistance` | `0` | `0` means the cascade ray's own near |
| `farDistance` | `0` | `0` means the cascade ray's own far |
| `maxRadiance` | `1.0e6` | per-segment firefly clamp; finite and `> 0` |
| `environmentPolicy` | `Disabled` | `Disabled` or `TerminalCascade` |
| `environmentCascade` | last cascade | must be a valid cascade for `TerminalCascade` |
| `worldBoundaryDistance` | `0` | `0` means no proven boundary; no environment |
| `flags` | `0` | reserved; must be zero |

`rcTraceOptionsValid` rejects malformed options.  `rcTraceRayValid` requires a
finite origin/direction, finite non-negative distances and a unit direction.

## Environment policy

A miss is not automatically sky.  `TerminalCascade` admits environment only
when all of the following hold:

1. `cascade == options.environmentCascade`;
2. `options.worldBoundaryDistance > 0`;
3. the trace's far distance reaches the boundary (`tFar >= boundary`).

The safe default is `Disabled`; a host that composites environment separately
keeps it.  A zero boundary is the absence of proof, not "trust the terminal
cascade".  For the native c4 range, the host must raise the terminal cascade's
far override to the real world boundary before expecting sky.

Environment is added to the far segment after the far filter:

```
L_far += T_far * environment.radiance(direction)
```

and the segment is flagged `ENVIRONMENT`.  The compose guard prevents a second
sky contribution behind it.

## Entry points

### Single ray, no store

```slang
RCTraceResult traceCascadeRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceRay ray, uint cascade,
        MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed);
```

Returns `nearSegment`, `farSegment`, `composed`, `distance`, `hit`, per-segment
events and ray-level aggregate flags.  Writes no buffer.  Use this for tests,
tooling, or a host that owns a different store.

### One ray, typed store

```slang
RCTraceResult rcTraceAndStoreRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceCascadeLayout layout, RWStructuredBuffer<RCSegment> segments,
        uint capacity, uint cascade, uint probeIndex, uint rayIndex,
        float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions, RCTraceOptions options,
        inout uint seed);
```

`stored == true` means both addresses were written, not that the ray resolved.
On an address failure nothing is written and `RC_TRACE_FLAG_STORE_FAILED` is
set.  On a valid address, an invalid segment is normalized to unresolved but
keeps `RC_SEGMENT_TRUNCATED` as a diagnostic.

### One probe

```slang
uint traceCascadeProbe<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        ..., RadianceCascadeLayout layout,
        RWStructuredBuffer<RCSegment> segments, uint capacity,
        uint cascade, uint probeIndex, float3 cameraOrigin,
        uint frameRotation, MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed);
```

Returns the number of rays whose address pair was not written.  A zero return
does not mean every segment resolved.

### One dispatch item

```slang
uint traceCascadeDispatch<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        ..., RadianceCascadeLayout layout,
        RWStructuredBuffer<RCSegment> segments, uint capacity,
        uint globalIndex, float3 cameraOrigin, uint frameRotation,
        MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed);
```

`decodeRadianceDispatch(globalIndex)` maps a flat dispatch index to
`(cascade, probeIndex, rayIndex)`.  A stale oversized dispatch decodes to the
cascade sentinel and writes nothing.  Total work is the sum of
`probeCount(desc) * desc.rayCount` over all cascades.

The host should size the dispatch from the same layout functions; do not
hard-code a work count.  `radianceCascadeLayout()` and
`decodeRadianceDispatch()` are the single source of truth.

## Layout, capacity and addressing

[`rc_atlas_layout.slang`](rc_atlas_layout.slang) owns:

```slang
RadianceCascadeLayout layout = radianceCascadeLayout();
uint index = rcIntervalIndex(layout, cascade, probeIndex, rayIndex, interval);
```

- `interval == 0` is near, `interval == 1` is far.
- `layout.totalRecords` is the minimum element count.
- `rcSegmentIndex` (typed store) re-derives the canonical layout and rejects a
  fabricated layout, a non-canonical `baseOffset`, a capacity smaller than
  `totalRecords`, and any out-of-range component.
- `capacity` is the descriptor-range element count, not bytes and not the
  allocation's full size.

`segment_store.slang` exposes whole-record operations:

```slang
RCSegment rcReadSegment(StructuredBuffer<RCSegment> records, ...);
RCSegment rcReadSegment(RWStructuredBuffer<RCSegment> records, ...);
bool rcWriteSegment(RWStructuredBuffer<RCSegment> records, ...);
bool rcWriteRaySegments(RWStructuredBuffer<RCSegment> records, ...);
RCSegment rcResolveRay(...);
float3 rcProbeIrradiance(...);
bool rcProbeComplete(...);
```

`rcProbeIrradiance` returns a cosine-weighted average radiance, not a
π-scaled irradiance.  It skips rays whose composition is unresolved; use
`rcProbeComplete` when the caller needs a completeness verdict.

## History (provisional)

`rc_trace_history.slang` is opt-in and explicitly heuristic.  It provides:

```slang
struct RCTemporalHistory {
    StructuredBuffer<RCSegment> segments;
    uint   historyCapacity;
    float3 previousCameraOrigin;
    uint   previousRotation;
    uint   previousSceneRevision;
    uint   currentSceneRevision;
    float  weight;
    bool   valid;
};

bool rcHistoryProbeIndex(...);
bool rcHistoryUsable(...);
uint rcHistoryRayIndex(...);
bool rcReadMappedHistoryRay(...);
bool rcProvisionalBlendRaySegments(...);
bool rcProvisionalBlendRayHistory(...);
uint traceCascadeProbeBlended(...);
uint traceCascadeDispatchBlended(...);
```

The current probe position is mapped into the previous clipmap lattice, and
the ray direction is mapped through the current/previous rotations.  History is
rejected when the scene revision differs, the probe is off the lattice, or any
required segment is unresolved/truncated.

The blend lerps `L` and `T` independently and requires matching terminal classes
(`BLOCKED` / `BOUNDED_MISS` / `ENVIRONMENT`).  That is not an unbiased temporal
resolve: path/filter topology is not encoded in the record.  The resolve owner
must define filter weights, clipping, status transitions and cross-cascade
history before treating it as a guarantee.

## Cross-cascade ownership (proposal only)

`rc_trace_ownership.slang` provides a non-overlapping distance partition:

```slang
RCCascadeOwnershipBand band;
bool ok = rcCascadeOwnershipBand(cascade, band);
RCTraceOptions bandOptions = rcApplyCascadeOwnershipBand(options, cascade);
```

Band `c` is `(far(c-1), far(c)]`; band 0 starts at the native near plane.
`band.splitDistance` is the near/far midpoint.  Adjacent closed bands share a
boundary, and distances are measured from each probe origin, not from the
shading point.  The helper does not implement a resolve, weights or an
environment handoff.  Probe lattices and ray sets differ across cascades, so
records from different cascades cannot be zipped or composed by index.

## Host integration sequence

1. Build `RadianceCascadeLayout layout = radianceCascadeLayout()` on the host and
   validate it against the shader-side function during pipeline setup.
2. Allocate at least `layout.totalRecords` `RCSegment` elements (32-byte stride
   confirmed by reflection).  Keep separate current and history buffers if the
   provisional path is used.
3. Bind `MaterialAtlas`, `BlockMaterialTable`, the selected `ISceneTracer`
   backend, an `ILightSampler`, an `IEnvironmentSampler` and the typed atlas.
4. Dispatch `traceCascadeProbe` per probe, or `traceCascadeDispatch` over
   `sum(probeCount * rayCount)`, or `rcTraceAndStoreRay` for a controlled
   subset.
5. Wait for the trace pass to complete before any resolve reads the atlas.  The
   two segment writes are not an atomic pair.
6. Resolve with `rcResolveRay`, `rcProbeIrradiance` and `rcProbeComplete`.
   Skip unresolved records; never treat them as black.
7. If environment is desired, opt in explicitly and prove the world boundary.
8. If history is desired, use the provisional entries only after the previous
   frame is complete and the scene revision policy is defined.

## Failure taxonomy

| Symptom | Meaning | Correct host response |
|---|---|---|
| `stored == false` | address pair was not written | treat the record as absent; do not read stale data |
| `RC_TRACE_FLAG_STORE_FAILED` | same as above, ray-level | fix layout/capacity; do not retry blindly |
| `RC_SEGMENT_TRUNCATED` | a budget ended the segment | keep it diagnostic; do not use as black |
| `RC_TRACE_EVENT_INVALID` | malformed arithmetic/backend data | reject the sample; do not average it |
| `RC_SEGMENT_BOUNDED_MISS` | miss inside the bounded window | not sky; environment is a separate policy |
| `RC_SEGMENT_ENVIRONMENT` | sky was added by its owner | do not compose another segment behind it |
| `rcProbeComplete == false` | at least one ray did not resolve | use a cheaper update or retry; not a clamp |

## Verification

Compile harnesses:

- `common/src/test/resources/shaders/slang/verify_rc_typed.slang`
- `common/src/test/resources/shaders/slang/verify_rc_environment.slang`
- `common/src/test/resources/shaders/slang/verify_rc_trace_api.slang`
- `common/src/test/resources/shaders/slang/verify_bedray_roots.slang`

Command:

```powershell
slangc -target spirv -profile cs_6_5 -entry main `
  -matrix-layout-column-major -I common/src/main/resources/shaders/slang `
  -o "$env:TEMP\verify_rc_trace_api.spv" `
  common/src/test/resources/shaders/slang/verify_rc_trace_api.slang
```

A green run is L1 only.  GPU execution, reflected stride validation and visual
parity remain open.
