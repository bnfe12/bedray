# Radiance interval transport

`interval_transport.slang` defines the segment-local radiance/transmittance
record and front-to-back composition.  Its flag bits describe events seen in
the segment or composed segments; they are not an exclusive terminal-state
enum.  An unresolved or truncated input cannot become a resolved black sample.
Exactly opaque foreground transport can be resolved without a back sample.

## Modules

| Module | Role |
|---|---|
| `rc_atlas_layout.slang` | record-neutral `(cascade, probe, ray, interval)` addressing shared by both atlases |
| `interval_transport.slang` | `RCSegment`, validity flags, `rcComposeSegments` |
| `segment_record.slang` | typed whole-record read/write and near/far ray composition |
| `probe_resolve.slang` | typed probe radiance/completeness resolve |
| `segment_store.slang` | facade re-exporting `segment_record` and `probe_resolve` |
| `rc_trace.slang` | typed probe-ray trace facade (see `RC_TRACE_API.md`) |
| `legacy_float4_store.slang` | **legacy** float4 records and confidence-weighted resolve |
| `radiance_cascades_store.slang` | **legacy** compatibility facade over the float4 store |

The typed atlas and the legacy float4 atlas are different formats.  A float4
record stores `(radiance.rgb, confidence)`; an `RCSegment` stores
`(radiance.rgb, confidence, transmittance.rgb, flags)` at a 32-byte stride.
Never bind one format to the other's descriptor.

## Record semantics

- `L` is the radiance emitted or reflected inside the segment, attenuated only
  by filters crossed earlier *inside the same segment*.
- `T` is the product of the segment's filter colours.  It is not pre-applied to
  `L`.
- `confidence` is status metadata, not an interpolation weight.  An opaque near
  interval must extinguish the far interval regardless of confidence.
- `RC_SEGMENT_TRUNCATED` is a diagnostic on an otherwise unresolved record; it
  is not a value.  A record without `RESOLVED` is untrusted, never black.

Consumers compose the near/far pair with:

```
L_total = L_near + T_near * L_far
T_total = T_near * T_far
```

`rcComposeSegments` also returns the front unchanged when `T_front` is exactly
zero, and refuses to compose anything behind an `ENVIRONMENT` segment.

## Typed store contract

`segment_store.slang` is a facade over `segment_record.slang` (whole-record
access and ray composition) and `probe_resolve.slang` (probe reconstruction).
Its capacity is a host-supplied descriptor-range element count, not a GPU bounds
query; the host must validate that count and the reflected stride.  Record
writes require exclusive ownership and GPU synchronization.  A failed
valid-address write normalizes the record to unresolved; invalid addresses do
not write.  Unresolved records have zero values and preserve `TRUNCATED` when
supplied.  Other trace events, including invalid-input diagnostics, are not
represented by this atlas.

`rcResolveProbe` integrates only rays whose composed segment is valid.  Each
ray contributes the radiance of `rcComposeSegments(front, back)` with a cosine
weight; confidence is never used as a blend weight, so an opaque front interval
cannot be averaged with a bright back interval.  Rays that do not resolve are
skipped rather than treated as black.  It returns `RCProbeRadiance`, which
separates `radiance`, `validRays`, `totalRays`, `complete`, `layoutValid` and
`probeValid`; a partial probe is a biased estimate and the caller must check
`complete`.  `rcProbeComplete` is the matching predicate: it requires every ray
to resolve, and an exactly opaque front resolves without a back sample.
`rcResolveProbe` returns a cosine-weighted average radiance, not a π-scaled
irradiance.

`rcWriteRaySegments` validates both addresses before writing and preserves a
valid interval even if the other is unresolved.  Its success result means the
addresses were written, not that the ray resolved.  The two writes are not an
atomic transaction; a consumer must not read them until the producer pass is
complete and the host has supplied the required GPU memory dependency.

The resolve contract can be checked without a GPU: opaque near (`T = 0`) plus
any far must return the near radiance; transparent near (`L = 0`, `T = 1`) must
return the far radiance; a transmissive near with an unresolved far must remain
unresolved and make the probe incomplete.

## Typed trace

`rc_trace.slang` is the typed trace facade.  It writes segment-local records
through `rcWriteRaySegments`; it does not use the float4 resolve.  The module
split, entry points and host obligations are in
[`RC_TRACE_API.md`](RC_TRACE_API.md).

## Cross-cascade resolve

Cross-cascade resolve requires more than composing successive records.  Current
cascade ranges overlap (`spacing * 0.5` to `spacing * 4`), and probe origins and
directions differ across levels.  The integration must define how to map
spatial/angular samples and select non-overlapping path segments before it can
apply the transport equation.  Environment ownership and temporal reuse also
need matching validity, revision and status rules.  `rc_trace_ownership.slang`
offers only a distance-band proposal, not a resolve.

No GPU memory budget or rendering result has been measured for this record.

## Verification

`verify_rc_typed.slang` compiles the typed store and resolve without importing
the legacy store.  `verify_rc_trace_api.slang` instantiates the full trace API
with mock backends.  Both are L1 compile evidence; they do not execute a
dispatch or validate a reflected stride.

For planning only: the current fixed constants produce 2,031,616 records per
atlas (two intervals per ray).  If reflection confirms a 32-byte stride, that is
62 MiB per atlas, or 124 MiB for two equally sized atlases, before allocator
overhead and other resources.  This is arithmetic, not a measured allocation;
the host must use the actual reflected stride and account for all frames in
flight and retained history rather than hard-coding this estimate.
