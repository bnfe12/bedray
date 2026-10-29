// Typed Radiance Cascades trace - public facade.
//
// This path used to be one 1300-line module.  It is now a set of focused
// modules with one-way dependencies.  An entry pass keeps importing this single
// path and receives the whole public surface:
//
//   rc_trace_options.slang    options, diagnostics, result, environment policy
//   rc_trace_shade.slang      first-surface emission + NEE
//   rc_trace_walk.slang       segment finalization and the interval walk
//   rc_trace_dispatch.slang   store, probe and dispatch entries
//   rc_trace_history.slang    provisional temporal history (opt-in)
//   rc_trace_ownership.slang  distance-band proposal (opt-in)
//
// Dependency direction is one-way:
//
//   options <- shade <- walk <- dispatch <- history
//   options <- ownership
//
// No module here imports the legacy float4 store or rc_trace_segments.
//
// Transport model
// ---------------
// The ray range [tNear, tFar] is split at mid.  Both records are segment-local
// and are composed only by rcComposeSegments:
//
//   L_total = L_near + T_near * L_far
//   T_total = T_near * T_far
//
// A stored record without RESOLVED, or with RC_SEGMENT_TRUNCATED, is untrusted;
// it is never a trusted black.  Exactly zero transmittance is the only terminal
// absorption.  Environment is added only to the far segment by the terminal
// cascade owner and only when a positive world boundary is proven.
//
// Host contract and extension points are in radiance_cascades/RC_TRACE_API.md.
// Module responsibilities and dependency rules are in ../ARCHITECTURE.md.

public module rc_trace;

__exported import "radiance_cascades/rc_trace_options.slang";
__exported import "radiance_cascades/rc_trace_shade.slang";
__exported import "radiance_cascades/rc_trace_walk.slang";
__exported import "radiance_cascades/rc_trace_dispatch.slang";
__exported import "radiance_cascades/rc_trace_history.slang";
__exported import "radiance_cascades/rc_trace_ownership.slang";
