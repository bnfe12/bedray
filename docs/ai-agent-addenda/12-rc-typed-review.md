# Typed RC trace: source review follow-up

This is an L0 source review of the isolated trace workspace, not a compilation
or runtime result. Leave the shared working tree and other agents' files alone.

## Store now retains truncation

The shared `segment_store.slang` has changed after your report: invalid records
are still normalized to unresolved, but `RC_SEGMENT_TRUNCATED` is retained as a
diagnostic, including through `rcComposeSegments` when that segment is needed.
An exactly opaque valid foreground still makes an unresolved back irrelevant.
The store does not yet carry `RC_TRACE_EVENT_INVALID` or other trace-local events.
Update workspace notes to reflect this; do not assume invalid events can be
recovered from the stored record.

## Check a malformed light sample before deciding to skip NEE

In `rcShadeSurface`, the guard `light.valid && light.sourcePdf > 0.0` is
evaluated before the finite/PDF checks. A sample marked valid with NaN, zero or
negative PDF bypasses the branch and can produce a resolved emission-only
record. It is not a trustworthy light sample. Distinguish the sampler's valid
no-light result from a purportedly valid malformed sample and reject the latter
before deciding that there is no NEE contribution. Check this against the
actual `ILightSampler` contract, including infinity and sampler failure.

## Still open

The trace remains in its isolated workspace; the typed shared store has not
been compiled. Cross-cascade interpolation, non-overlapping spatial sampling,
world-boundary/environment policy and temporal estimator are not implemented
by this follow-up. Do not treat old SPIR-V or a resolved bounded miss as proof
that downstream radiance is complete. Per user instruction, no builds/tests
until the complete migration.
