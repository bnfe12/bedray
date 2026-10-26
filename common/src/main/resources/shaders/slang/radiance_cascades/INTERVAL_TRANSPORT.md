# Radiance interval transport: source candidate

`interval_transport.slang` defines a segment-local radiance/transmittance
record and front-to-back composition. Its flag bits describe events seen in
the segment or composed segments; they are not an exclusive terminal-state
enum. An unresolved or truncated input cannot become a resolved black sample.
Exactly opaque foreground transport can be resolved without a back sample.

`segment_store.slang` provides typed record access, clearing and near/far
composition using the current canonical cascade addressing. Its capacity is a
host-supplied descriptor-range element count, not a GPU bounds query; the host
must validate that count and the reflected stride. Record writes require
exclusive ownership and GPU synchronization. A failed valid-address write
normalizes the record to unresolved; invalid addresses do not write. Unresolved
records have zero values and preserve `TRUNCATED` when supplied. Other trace
events, including invalid-input diagnostics, are not represented by this atlas.
There is no temporal blending or cross-cascade resolve.

`rcWriteRaySegments` validates both addresses before writing and preserves a
valid interval even if the other is unresolved. Its success result means the
addresses were written, not that the ray resolved. The two writes are not an
atomic transaction; a consumer must not read them until the producer pass is
complete and the host has supplied the required GPU memory dependency.

These are uncompiled, unintegrated source candidates. They do not change the
existing `float4` atlas, trace workspace, temporal blending or resolve. The
trace owner and storage owner must agree on a single record and flags before
using it, and shader reflection must establish the actual buffer layout. In
particular, never feed the transitional pre-attenuated far record from the
trace workspace to this composition: it requires segment-local far radiance.

Cross-cascade resolve requires more than composing successive records. Current
cascade ranges overlap (`spacing * 0.5` to `spacing * 4`), and probe origins
and directions differ across levels. The integration must define how to map
spatial/angular samples and select non-overlapping path segments before it can
apply the transport equation. Environment ownership and temporal reuse also
need matching validity, revision and status rules. No GPU memory budget or
rendering result has been measured for this record.

For planning only: the current fixed constants produce 2,031,616 records per
atlas (two intervals per ray). If reflection confirms a 32-byte stride, that is
62 MiB per atlas, or 124 MiB for two equally sized atlases, before allocator
overhead and other resources. This is arithmetic, not a measured allocation;
the host must use the actual reflected stride and account for all frames in
flight and retained history rather than hard-coding this estimate.
