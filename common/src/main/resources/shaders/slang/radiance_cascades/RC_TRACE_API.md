# Typed Radiance Cascades trace API

Status: source contract, compile-checked with `slangc`.  No GPU execution or
live-frame evidence is claimed here.  The typed trace is consumed by the compiled
field passes `passes/rc_field_build_scene.slang` and `passes/rc_path_trace_scene.slang`
(plus its `.raygen` / `.ser` variants) through `field_transport.slang`; the former
compile-only `rc_probe_trace.slang` example and the `rc_trace.slang` facade were
deleted with the typed-store/dispatch API.

This document is the contract between a caller and the typed Radiance Cascades
trace.  The single public entry is `traceCascadeRay` in
[`rc_trace_walk.slang`](rc_trace_walk.slang).  Record semantics and composition
live in [`interval_transport.slang`](interval_transport.slang) and are documented
in [INTERVAL_TRANSPORT.md](INTERVAL_TRANSPORT.md).

## Module layout

```
rc_atlas_layout.slang         record-neutral (cascade, probe, ray, interval) addressing
interval_transport.slang      RCSegment and front-to-back composition
rc_trace_options.slang        options, events, result, environment policy
rc_trace_shade.slang          first-surface emission + NEE
rc_trace_walk.slang           interval walk + the single-ray entry traceCascadeRay

field_mapping.slang           versioned cubemap cells + one haloed spatial lattice
field_transport.slang         field trace/compose/reconstruct/history over the walk
field_gather.slang            RCPathDiffuseField, the path tracer's diffuse continuation
```

Dependency direction is one-way:

```
rc_atlas_layout <- interval_transport <- rc_trace_options <- rc_trace_shade <- rc_trace_walk
field_mapping <- field_transport <- field_gather
```

`rc_trace_walk.slang` re-exports the trace surface (`rc_trace_options`,
`rc_trace_shade`, `interval_transport` and the shared contracts), so a caller
normally imports only the walk — or `field_transport` for the field path.  A
plain `import` is not re-exported.  The trace does not import any float4 store;
that format was deleted outright.

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

The reflected layout is 32 bytes: offsets 0, 12, 16 and 28.  The Gradle build
pins this in `STRUCT_LAYOUTS`, and the host must independently verify the
reflected stride of the descriptor it binds.  The shader cannot check a host
record stride.

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
`traceCascadeRay` reports an invalid cascade, option set or ray through
`RC_TRACE_FLAG_INVALID_INPUT` and an unresolved `composed` record
(`distance == 0`), never a trusted black sample; the caller checks
`rcSegmentValid(result.composed)`.  An empty window (`tFar <= tNear`) is instead
a resolved identity record (`L = 0`, `T = 1`) flagged `RC_TRACE_FLAG_EMPTY_WINDOW`.

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

## Entry point

### Single ray, no store

```slang
RCTraceResult traceCascadeRay<T : ISceneTracer, L : ILightSampler, E : IEnvironmentSampler>(
        T traversal, L lights, E environment,
        MaterialAtlas atlas, BlockMaterialTable blockMaterials,
        RadianceRay rayValue, uint cascade, MaterialSamplingOptions materialOptions,
        RCTraceOptions options, inout uint seed);
```

The only shader trace entry.  It walks `[tNear, tFar]`, splits at the midpoint
and returns `nearSegment`, `farSegment`, `composed`
(`rcComposeSegments(nearSegment, farSegment)`), `distance`, `hit`, `cascade`,
per-segment `nearEvents`/`farEvents` and the ray-level `flags` aggregate.  It
writes no buffer; the caller owns any storage.  Both a light sampler and an
environment sampler are required — there is no neutral sampler that turns a
missing binding into black.  With the default `Disabled` environment policy the
environment sampler is not consulted until the host opts in.

`field_transport.slang` (`rcFieldTrace`) is the live caller: it builds a
segment-local ray, forces `Disabled` environment and zero emission scale (the
primary path already owns direct emitter NEE), and keeps `result.composed`.

## Layout, capacity and addressing

[`rc_atlas_layout.slang`](rc_atlas_layout.slang) owns the shared linear layout:

```slang
RadianceCascadeLayout layout = radianceCascadeLayout();
bool canonical = rcLayoutCanonical(layout, capacity);
uint index = rcUncheckedIntervalIndex(layout, cascade, probeIndex, rayIndex, interval);
// index == RC_INVALID_RECORD when an address component is out of range
```

- `RC_INTERVAL_NEAR` is `0`, `RC_INTERVAL_FAR` is `1`; `rc_trace_options`
  aliases them as `RCT_INTERVAL_NEAR`/`RCT_INTERVAL_FAR`.
- `layout.totalRecords` is the minimum element count.
- `layout.flags` is reserved and must be zero; a non-zero value is rejected.
- `rcLayoutCanonical` re-derives the canonical layout and rejects a fabricated
  layout, a mismatched per-cascade entry, a non-zero reserved flag and a
  capacity smaller than `totalRecords`.
- `rcUncheckedIntervalIndex` performs address arithmetic and a range check only
  (returning `RC_INVALID_RECORD` when a component is out of range); it does NOT
  validate the layout.  Call `rcLayoutCanonical` once per probe or ray first.
- `capacity` is the descriptor-range element count, not bytes and not the
  allocation's full size.
- `decodeRadianceDispatch(globalIndex)` (in `radiance_cascades.slang`) maps a flat
  dispatch index to a `RadianceProbeAddress` `(cascade, probeIndex, rayIndex)`,
  with `cascade == RADIANCE_CASCADE_COUNT` as the overflow sentinel.  Total work
  is `sum(probeCount(desc) * desc.rayCount)` over all cascades; size it from the
  layout functions, never a hard-coded count.

## Host integration sequence

The only compiled consumers are the field passes; there is no probe/dispatch
store entry point.  The host drives them as follows:

1. Build `RCFieldConstants field` on the host (lattice counts, per-level ranges,
   `tag.x == frameIndex`, `history.xyzw`) and validate it with
   `rcFieldMetadataValid`.  The build pass rejects a stale tag or an invalid
   field before writing anything.
2. Allocate the `RCSegment` tail buffer (32-byte stride confirmed by reflection)
   sized from the layout functions, plus a history buffer holding the previous
   recording's tails.  Bind `rcField` (constant buffer), `rcTails`
   (`RWStructuredBuffer<RCSegment>`) and `rcHistory`
   (`StructuredBuffer<RCSegment>`); when `history.w == 0` the history buffer may
   alias the current tails and is never read.
3. Bind `MaterialAtlas`, `BlockMaterialTable`, the selected `ISceneTracer`
   backend, an `ILightSampler` and an `IEnvironmentSampler`, exactly as
   [`rc_field_build_scene.slang`](../passes/rc_field_build_scene.slang) declares.
4. Dispatch one compute pass per level, terminal to finest, with a native
   write/read memory barrier between levels so a finer level reads the coarser
   tails only after they are written.  Each item calls `rcFieldReconstruct`,
   which traces the top level directly and rebuilds lower levels from four child
   cells times eight trilinear neighbours via `rcFieldTrace` -> `traceCascadeRay`.
5. `rcFieldReconstruct` forces `RCEnvironmentPolicy.Disabled` and
   `emissionScale = 0`: the field carries one indirect bounce of reflected
   direct light, while the primary path owns emitter NEE.  Environment is opt-in
   elsewhere and must prove a positive world boundary the far window can reach.
6. Before the next finer level reads a cell, `rcFieldBlendHistory` mixes it with
   the previous recording's field as an exponential mean whose weight the native
   owner supplies in `history.w` (0 on the first frame, a reset or a skipped
   frame).  An unresolved current cell keeps its own unresolved value; an
   unresolved or off-lattice history neighbour drops history for that cell
   rather than being renormalised away.
7. Gather with `RCPathDiffuseField`
   ([`field_gather.slang`](field_gather.slang)) in the path-trace pass: it
   composes a freshly traced front segment with the stored tail, requires every
   contributing sample to be valid, and returns a cosine-weighted irradiance
   divided by π.  Outside the supported region it reports `DIFFUSE_FIELD_OUTSIDE`
   and the ordinary path continues; an invalid sample reports
   `DIFFUSE_FIELD_INVALID`.  Never present an unresolved cell as black.

## Failure taxonomy

| Symptom | Meaning | Correct host response |
|---|---|---|
| `RC_TRACE_FLAG_INVALID_INPUT` | cascade, options or ray failed validation | fix the host input; the result is fail-closed unresolved, distance 0 |
| `RC_TRACE_FLAG_EMPTY_WINDOW` | `tFar <= tNear`; no geometry and no direction segment | a resolved identity record (`L = 0`, `T = 1`), not a black fallback |
| `rcSegmentValid(composed) == false` | the composed record did not resolve | skip the sample; never treat it as black or average it in |
| `RC_SEGMENT_TRUNCATED` | a budget ended the segment | keep it diagnostic on an unresolved record; do not use as a value |
| `RC_TRACE_EVENT_INVALID` | malformed arithmetic/backend data | reject the sample; do not average it |
| `RC_SEGMENT_BOUNDED_MISS` | miss inside the bounded window | not sky; environment is a separate opt-in policy |
| `RC_SEGMENT_ENVIRONMENT` | sky was added by its owner | do not compose another segment behind it |
| `DIFFUSE_FIELD_OUTSIDE` | shading point outside the supported field region | continue the ordinary path; this is not an error |
| `DIFFUSE_FIELD_INVALID` | a contributing sample did not resolve | fall back to the ordinary path; do not present a partial estimate |

## Verification

The trace API is exercised without a GPU by:

- CPU-reference Java tests under
  `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/rc/`
  (`RcTransportTest`, `RcReferenceTest`, `RcGeometryTest`, `RcProjectedGatherTest`),
  which check the composition and gather math against an independent reference.
- Source guards `RtSlangRadianceTransportTest`, `RtRcFieldHistoryTest` and
  `RtSlangRcFieldTest`, which read the shader text and pin the record layout,
  flag vocabulary and history rules.
- The native `bedray_rc_field_pass_test`
  (`native/bedray_core/tests/rc_field_pass_test.cpp`), which validates the build
  pass's binding set, push-block size and inter-level memory barriers against a
  fake device.  It does not run a GPU dispatch.

AOT compilation is `compileSlang` in `gradle/slang.gradle`:
it compiles each `passes/*.slang` entry (including `rc_field_build_scene` and
`rc_path_trace_scene`) to SPIR-V plus a manifest with `-target spirv -profile
cs_6_5 -entry main -matrix-layout-column-major`.  The standalone harnesses that
remain (`verify_water`, `verify_denoiser_signals`, `verify_triangle_intersection`)
do not cover RC transport.

Compilation and these guards are L1 evidence only.  No GPU execution, reflected
stride validation, memory budget or live-frame visual parity is claimed here.
