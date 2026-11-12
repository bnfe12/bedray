# Radiance interval transport

`interval_transport.slang` defines the segment-local radiance/transmittance
record and front-to-back composition.  Its flag bits describe events seen in
the segment or composed segments; they are not an exclusive terminal-state
enum.  An unresolved or truncated input cannot become a resolved black sample.
Exactly opaque foreground transport can be resolved without a back sample.

## Modules

| Module | Role |
|---|---|
| `radiance_cascades.slang` (parent dir) | `RadianceRay`, cascade descriptors, `cascadeRay`, `decodeRadianceDispatch` |
| `rc_atlas_layout.slang` | record-neutral `(cascade, probe, ray, interval)` addressing: `RadianceCascadeLayout`, `radianceCascadeLayout`, `rcLayoutCanonical`, `rcUncheckedIntervalIndex` |
| `interval_transport.slang` | `RCSegment`, validity flags, `rcSegmentValid`, `rcComposeSegments` |
| `rc_trace_options.slang` | `RCTraceOptions`, `RCTraceResult`, per-segment events, ray-level flags, environment policy |
| `rc_trace_shade.slang` | first-surface emission plus at most one NEE sample |
| `rc_trace_walk.slang` | the interval walk and the single-ray entry `traceCascadeRay` |
| `field_mapping.slang` | versioned cubemap cells and one shared, haloed spatial lattice (`RCFieldConstants`) |
| `field_transport.slang` | field trace/compose/reconstruct/history over the walk (`rcFieldTrace`, `rcFieldReconstruct`, `rcFieldBlendHistory`) |
| `field_gather.slang` | `RCPathDiffuseField : IPathDiffuseField`, the path tracer's diffuse continuation |

The typed `RCSegment` atlas is the only interval format.  The float4
confidence-weighted store and its compatibility facade were deleted; no shader
writes a legacy interval, so an averaging resolve can no longer reach a frame.
`RtSlangRadianceTransportTest` guards that deletion and the composition rules
below.

## Record semantics

`RCSegment` is 32 bytes: `radiance` (offset 0), `confidence` (12),
`transmittance` (16), `flags` (28).  `gradle/slang.gradle` pins this stride in
`STRUCT_LAYOUTS`; the host must independently verify the reflected stride of the
descriptor it binds.  The shader cannot check a host record stride.

- `L` (`radiance`) is the radiance emitted or reflected inside the segment,
  attenuated only by filters crossed earlier *inside the same segment*.
- `T` (`transmittance`) is the product of the segment's filter colours.  It is
  not pre-applied to `L`.
- `confidence` is status metadata, not an interpolation weight.  An opaque near
  interval must extinguish the far interval regardless of confidence.
- `RC_SEGMENT_TRUNCATED` is a diagnostic on an otherwise unresolved record; it
  is not a value.  A record without `RESOLVED` is untrusted, never black.

`rcSegmentValid` requires `RESOLVED` set, `TRUNCATED` clear, finite
radiance/transmittance/confidence, `confidence > 0`, and transmittance within
`[0, 1]`.

Flags (one bit each, scoped to a single segment):

| Flag | Meaning |
|---|---|
| `RC_SEGMENT_RESOLVED` | the record is a usable observation |
| `RC_SEGMENT_BLOCKED` | an opaque/zero-transmission event ends this segment |
| `RC_SEGMENT_BOUNDED_MISS` | the segment traversed its window without environment |
| `RC_SEGMENT_ENVIRONMENT` | this segment owns the sky sample; nothing may compose behind it |
| `RC_SEGMENT_TRUNCATED` | a budget ended the segment; not a resolved value |
| `RC_SEGMENT_CLAMPED` | the firefly bound changed the value |

## Composition

Consumers compose the near/far pair with:

```
L_total = L_near + T_near * L_far
T_total = T_near * T_far
confidence = min(confidence_near, confidence_far)
flags = flags_near | flags_far
```

`rcComposeSegments(front, back)` enforces, in order:

1. an invalid front returns unresolved, preserving the front's `TRUNCATED`
   diagnostic;
2. an exactly zero front transmittance returns the front unchanged (a small
   nonzero value is not proof of occlusion);
3. an `ENVIRONMENT` front returns unresolved — the sky contribution is already
   counted, so nothing may compose behind it;
4. an invalid back returns unresolved, preserving the back's `TRUNCATED`;
5. otherwise it composes as above, and a non-finite composed radiance returns
   unresolved.

Confidence is never a blend weight.  The resolve contract can be checked without
a GPU: opaque near (`T = 0`) plus any far returns the near radiance; transparent
near (`L = 0`, `T = 1`) returns the far radiance; a transmissive near with an
unresolved far stays unresolved.

## Live field path

The walk is consumed by the field passes, not by a generic store/dispatch facade:

```text
passes/rc_field_build_scene.slang
  -> field_transport.slang (rcFieldReconstruct)
     -> field_mapping.slang + interval_transport.slang + rc_trace_walk.slang
        -> rc_trace_shade.slang -> rc_trace_options.slang
passes/rc_path_trace_scene.slang (+ .raygen / .ser)
  -> field_gather.slang (RCPathDiffuseField) -> field_transport.slang
  -> pathtrace/path_trace_entry.slang -> pathtrace/path_tracer.slang
```

`rcFieldReconstruct` traces the top level directly; at lower levels it composes a
front trace with the back tail read from the next level, averaged over four
child angular cells and eight trilinear spatial neighbours.  `rcFieldAverageAdd`
/ `rcFieldAverageEnd` reject an unresolved or non-finite contributor instead of
renormalising it away, and only mark a cell terminal-known when every required
branch proves it.  `rcFieldBlendHistory` is an exponential temporal mean whose
weight the native owner supplies; an unresolved history neighbour drops the
history for that cell rather than biasing it.  Field storage uses direct typed
buffer accesses (`RWStructuredBuffer<RCSegment>`), not a store facade.

Cascade ranges overlap and probe origins/directions differ across levels, so
records from different cascades cannot be zipped or composed by index; the field
mapping owns the spatial/angular correspondence.  See [RC trace API](RC_TRACE_API.md)
for the single-ray entry, options, environment policy and addressing.

## Verification

The RC transport math is guarded without a GPU by the Java CPU-reference tests
under `common/src/test/java/io/github/andrexarlay/vulkancompat/rt/rc/`
(`RcTransportTest`, `RcReferenceTest`, `RcGeometryTest`, `RcProjectedGatherTest`)
and by the `RtSlangRadianceTransportTest` / `RtRcFieldHistoryTest` source guards.
The standalone slang harnesses that remain in the repository (`verify_water`,
`verify_denoiser_signals`, `verify_triangle_intersection`) do not cover RC
transport.  Compilation alone does not execute a dispatch or validate a reflected
stride; no GPU execution, memory budget or live-frame result is claimed here.
The host must size any atlas from the reflected stride and the layout functions,
accounting for all frames in flight and retained history, not a hard-coded estimate.
